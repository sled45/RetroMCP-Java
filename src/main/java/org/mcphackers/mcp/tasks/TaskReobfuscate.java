package org.mcphackers.mcp.tasks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.mcphackers.mcp.MCPConfig;
import org.mcphackers.mcp.ProgressInfo;
import org.mcphackers.mcp.tasks.info.TaskInfo;
import org.mcphackers.mcp.tools.FileUtil;
import org.mcphackers.mcp.tools.Util;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

public class TaskReobfuscate extends Task {
    private final Map<String, String> recompHashes = new HashMap<>();
    private final Map<String, String> originalHashes = new HashMap<>();

    private final Map<String, String> reobfClasses = new HashMap<>();
    private final Map<String, String> reobfPackages = new HashMap<>();
    private final Map<String, String> reobfMethods = new HashMap<>();
    private final Map<String, String> reobfFields = new HashMap<>();

    //private final Map<String, String> extraReobfClasses = new HashMap<>();
    //private final Map<String, String> extraReobfMethods = new HashMap<>();
    //private final Map<String, String> extraReobfFields = new HashMap<>();
    
	private TaskUpdateMD5 md5Task;

    public TaskReobfuscate(int side, TaskInfo info) {
        super(side, info);
        md5Task = new TaskUpdateMD5(side, info);
    }

    @Override
    public void doTask() throws Exception {

        Path reobfJar = Paths.get(chooseFromSide(MCPConfig.CLIENT_REOBF_JAR, 	MCPConfig.SERVER_REOBF_JAR));
        Path reobfBin = Paths.get(chooseFromSide(MCPConfig.CLIENT_BIN, 			MCPConfig.SERVER_BIN));
    	Path reobfDir = Paths.get(chooseFromSide(MCPConfig.CLIENT_REOBF, 		MCPConfig.SERVER_REOBF));
    	Path mappings = Paths.get(chooseFromSide(MCPConfig.CLIENT_MAPPINGS_RO, 	MCPConfig.SERVER_MAPPINGS_RO));

        step();
        md5Task.updateMD5(true);

        if (Files.exists(reobfBin)) {
            FileUtil.deleteDirectoryIfExists(reobfDir);
            step();
            gatherMD5Hashes(true, this.side);
            gatherMD5Hashes(false, this.side);

            step();
            readDeobfuscationMappings(this.side);
            writeReobfuscationMappings(this.side);
            
            Files.deleteIfExists(reobfJar);
            TinyRemapper remapper = null;

            try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(reobfJar).build()) {
                remapper = TaskDecompile.remap(TinyUtils.createTinyMappingProvider(mappings, "official", "named"), reobfBin, outputConsumer, TaskDecompile.getLibraryPaths(side));
            } finally {
                if (remapper != null) {
                    remapper.finish();
                }
            }
            step();
            unpack(reobfJar, reobfDir);
        } else {
        	throw new IOException(chooseFromSide("Client", "Server") + " classes not found!");
        }
    }

    @Override
    public ProgressInfo getProgress() {
    	int total = 100;
    	int current = 0;
    	switch (step) {
	    case 1: {
	    	current = 1;
	    	ProgressInfo info = md5Task.getProgress();
	    	int percent = (int)((double)info.getCurrent() / info.getTotal() * 50);
	        return new ProgressInfo(info.getMessage(), current + percent, total); }
	    case 2:
        	current = 51;
            return new ProgressInfo("Gathering MD5 hashes...", current, total);
	    case 3:
        	current = 52;
            return new ProgressInfo("Reobfuscating...", current, total);
	    case 4:
        	current = 54;
            return new ProgressInfo("Unpacking...", current, total);
	    default:
	    	return super.getProgress();
	    }
    }

    // Utility methods
    private void writeReobfuscationMappings(int side) throws IOException {
    	
        Path reobfBin = Paths.get(chooseFromSide(MCPConfig.CLIENT_BIN, 			MCPConfig.SERVER_BIN));
    	Path mappings = Paths.get(chooseFromSide(MCPConfig.CLIENT_MAPPINGS_RO, 	MCPConfig.SERVER_MAPPINGS_RO));
    	
        Files.walkFileTree(reobfBin, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".class")) {
                    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
                        private String className = "";

                        @Override
                        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                            className = name;
                            if (!reobfClasses.containsKey(name) && name.contains("Start")) {
                            	String key = name.lastIndexOf("/") >= 0 ? name.substring(0, name.lastIndexOf("/") + 1) : null;
                                String obfPackage = reobfPackages.get(key);
                                if(obfPackage == null) {
                                	obfPackage = "";
                                }
                            	String clsName = name.lastIndexOf("/") >= 0 ? name.substring(name.lastIndexOf("/") + 1) : name;
                            	reobfClasses.put(name, obfPackage + clsName);
                                //extraReobfClasses.put(name, name.replace("net/minecraft/src/", ""));
                            }
                            super.visit(version, access, name, signature, superName, interfaces);
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                            if (!reobfFields.containsKey(className + "/" + name) && !name.equals("$VALUES")) {
                                //extraReobfFields.put(className + "/" + name, className + "/" + name);
                                //System.out.println("Class-name: " + className + ", Field: " + name + ", Signature: " + descriptor);
                            }
                            return super.visitField(access, name, descriptor, signature, value);
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            if (!reobfMethods.containsKey(className + "/" + name) && !name.equals("<init>") && !name.equals("<clinit>")) {
                                //System.out.println("Class-name: " + className + ", Method name: " + name);
                            }
                            return super.visitMethod(access, name, descriptor, signature, exceptions);
                        }
                    };
                    ClassReader reader = new ClassReader(Files.readAllBytes(file));
                    reader.accept(visitor, 0);
                }
                return super.visitFile(file, attrs);
            }
        });

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(mappings.toFile()))) {
            writer.write("tiny\t2\t0\tofficial\tnamed\n");

            for (Map.Entry<String, String> classKeyPair : reobfClasses.entrySet()) {
                String deobfuscatedClassName = classKeyPair.getKey();
                String reobfuscatedClassName = classKeyPair.getValue();
                writer.write("c\t" + deobfuscatedClassName + "\t" + reobfuscatedClassName + "\n");

                for (Map.Entry<String, String> methodKeyPair : reobfMethods.entrySet()) {
                    String deobfuscatedFullName = methodKeyPair.getKey();
                    if (deobfuscatedFullName.startsWith(deobfuscatedClassName) && !deobfuscatedFullName.endsWith("<init>") && !deobfuscatedFullName.endsWith("<clinit>")) {
                        String deobfuscatedMethodName = deobfuscatedFullName.substring(methodKeyPair.getKey().lastIndexOf("/") + 1);
                        String reobfuscatedMethodName = methodKeyPair.getValue();

                        // 	m	(Lho;IIIII)V	a	renderQuad
                        String signature = reobfuscatedMethodName.substring(reobfuscatedMethodName.lastIndexOf("("));
                        String remappedSignature = remapSignature(signature);
                        writer.write("\tm\t" + remappedSignature + "\t" + deobfuscatedMethodName + "\t" + reobfuscatedMethodName.replace(signature, "") + "\n");
                    }
                }
                writer.flush();

                for (Map.Entry<String, String> fieldKeyPair : reobfFields.entrySet()) {
                    String deobfuscatedFullName = fieldKeyPair.getKey();
                    if (deobfuscatedFullName.startsWith(deobfuscatedClassName)) {
                        String deobfuscatedFieldName = deobfuscatedFullName.substring(fieldKeyPair.getKey().lastIndexOf("/") + 1);
                        String reobfuscatedFieldName = fieldKeyPair.getValue();

                        // 	m	(Lho;IIIII)V	a	renderQuad
                        String signature = reobfuscatedFieldName.substring(reobfuscatedFieldName.lastIndexOf("(") + 1, reobfuscatedFieldName.length() - 1);
                        String remappedSignature = remapSignature(signature);
                        writer.write("\tf\t" + remappedSignature + "\t" + deobfuscatedFieldName + "\t" + reobfuscatedFieldName.substring(0, reobfuscatedFieldName.indexOf("(")) + "\n");
                    }
                }
                writer.flush();
            }
        }
    }

    private String remapSignature(String signature) {
        String remappedSignature = "";
        StringBuilder builder = new StringBuilder();
        if (remapSignature(builder, signature, 0, signature.length())) {
            remappedSignature = builder.toString();
            builder.setLength(0);
        }
        if (remappedSignature.equals("") || remappedSignature.equals("()")) remappedSignature = signature;
        return remappedSignature;
    }

    private void readDeobfuscationMappings(int side) throws IOException {
    	Path mappings = Paths.get(chooseFromSide(MCPConfig.CLIENT_MAPPINGS, 	MCPConfig.SERVER_MAPPINGS));
    	
        try (BufferedReader reader = new BufferedReader(new FileReader(mappings.toFile()))) {
            String line = reader.readLine();
            String currentClassName = "";
            while (line != null) {
                String[] tokens = line.split("\t");

                // Tiny v2 uses indentation to denote level, so classes are always before methods and fields
                if (line.startsWith("c")) {
                    // Class
                    // c	aa	net/minecraft/src/TextureCompassFX
                    reobfClasses.put(tokens[2], tokens[1]);
                    String deobfPackage = tokens[2].lastIndexOf("/") >= 0 ? tokens[2].substring(0, tokens[2].lastIndexOf("/") + 1) : "";
                    if(!reobfPackages.containsKey(deobfPackage)) {
                        String obfPackage 	= tokens[1].lastIndexOf("/") >= 0 ? tokens[1].substring(0, tokens[1].lastIndexOf("/") + 1) : "";
                    	reobfPackages.put(deobfPackage, obfPackage);
                    }
                    currentClassName = tokens[2];
                } else if (line.startsWith("\tm")) {
                    // Method
                    // m	(Lho;IIIII)V	a	renderQuad
                    reobfMethods.put(currentClassName + "/" + tokens[4], tokens[3] + tokens[2]);
                } else if (line.startsWith("\tf")) {
                    // Field
                    // 	f	D	k	currentAngle
                    reobfFields.put(currentClassName + "/" + tokens[4], tokens[3] + "(" + tokens[2] + ")");
                }

                // Read next line
                line = reader.readLine();
            }
        }
    }

    private boolean remapSignature(StringBuilder signatureOut, String signature, int start, int end) {
        if (start == end) {
            return false;
        }
        int type = signature.codePointAt(start++);
        switch (type) {
            case 'T':
                // generics type parameter
                // fall-through intended as they are similar enough in format compared to objects
            case 'L':
                // object
                // find the end of the internal name of the object
                int endObject = start;
                while(true) {
                    // this will skip a character, but this is not interesting as class names have to be at least 1 character long
                    int codepoint = signature.codePointAt(++endObject);
                    if (codepoint == ';') {
                        String name = signature.substring(start, endObject);
                        String newName = getKeyByValue(reobfClasses, name);
                        boolean modified = false;
                        if (newName != null) {
                            name = newName;
                            modified = true;
                        }
                        signatureOut.appendCodePoint(type);
                        signatureOut.append(name);
                        signatureOut.append(';');
                        modified |= remapSignature(signatureOut, signature, ++endObject, end);
                        return modified;
                    } else if (codepoint == '<') {
                        // generics - please no
                        // post scriptum: well, that was a bit easier than expected
                        int openingBrackets = 1;
                        int endGenerics = endObject;
                        while(true) {
                            codepoint = signature.codePointAt(++endGenerics);
                            if (codepoint == '>' ) {
                                if (--openingBrackets == 0) {
                                    break;
                                }
                            } else if (codepoint == '<') {
                                openingBrackets++;
                            }
                        }
                        String name = signature.substring(start, endObject);
                        String newName = getKeyByValue(reobfClasses, name);
                        boolean modified = false;
                        if (newName != null) {
                            name = newName;
                            modified = true;
                        }
                        signatureOut.append('L');
                        signatureOut.append(name);
                        signatureOut.append('<');
                        modified |= remapSignature(signatureOut, signature, endObject + 1, endGenerics++);
                        signatureOut.append('>');
                        // apparently that can be rarely be a '.', don't ask when or why exactly this occours
                        signatureOut.appendCodePoint(signature.codePointAt(endGenerics));
                        modified |= remapSignature(signatureOut, signature, ++endGenerics, end);
                        return modified;
                    }
                }
            case '+':
                // idk what this one does - but it appears that it works good just like it does right now
            case '*':
                // wildcard - this can also be read like a regular primitive
                // fall-through intended
            case '(':
            case ')':
                // apparently our method does not break even in these cases, so we will consider them raw primitives
            case '[':
                // array - fall through intended as in this case they behave the same
            default:
                // primitive
                signatureOut.appendCodePoint(type);
                return remapSignature(signatureOut, signature, start, end); // Did not modify the signature - but following operations could
        }
    }

    public static <T, E> T getKeyByValue(Map<T, E> map, E value) {
        for (Map.Entry<T, E> entry : map.entrySet()) {
            if (Objects.equals(value, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void gatherMD5Hashes(boolean reobf, int side) throws IOException {
        Path md5 = Paths.get(reobf ? chooseFromSide(MCPConfig.CLIENT_MD5_RO, MCPConfig.SERVER_MD5_RO)
        							  : chooseFromSide(MCPConfig.CLIENT_MD5, MCPConfig.SERVER_MD5));

        try (BufferedReader reader = new BufferedReader(new FileReader(md5.toFile()))) {
            String line = reader.readLine();
            while (line != null) {
                String[] tokens = line.split(" ");
                if (reobf) {
                    recompHashes.put(tokens[0], tokens[1]);
                } else {
                    originalHashes.put(tokens[0], tokens[1]);
                }

                // Read next line
                line = reader.readLine();
            }
        }
    }

    private void unpack(final Path src, final Path destDir) throws IOException {
    	FileUtil.unzip(src, destDir, entry -> {
            String fileName = entry.getName();
            String deobfName = Util.getKey(reobfClasses, fileName.replace(".class", ""));
            String hash = originalHashes.get(deobfName);
            return !entry.isDirectory() && ( hash != null && !hash.equals(recompHashes.get(deobfName)) || hash == null);
    	});
    }
}