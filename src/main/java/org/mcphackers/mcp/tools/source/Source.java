package org.mcphackers.mcp.tools.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class Source {

	public static final Pattern PACKAGE = Pattern.compile("package ([.*\\w]+);(\\r|)\\n");
	public static final Pattern IMPORT = Pattern.compile("import ([.*\\w]+);((\\r|)\\n)+");

	public abstract void apply(StringBuilder source);

	public static void modify(Path src, List<Source> modify) throws IOException {
		Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if(!file.getFileName().toString().endsWith(".java")) {
					return FileVisitResult.CONTINUE;
				}
				StringBuilder source = new StringBuilder(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				for(Source srcModify : modify) {
					srcModify.apply(source);
				}
				Files.write(file, source.toString().getBytes());
				return FileVisitResult.CONTINUE;
			}
		});
	}

	protected void updateImports(StringBuilder source, Set<String> imports) {
		replaceTextOfMatchGroup(source, IMPORT, match -> {
			// Add import to the set
			imports.add(match.group(1));
			// Remove import from source
			return "";
		});
		List<String> importsList = new ArrayList<>(imports);
		importsList.sort(Comparator.naturalOrder());
		StringBuilder sb = new StringBuilder();
		String n = System.lineSeparator();
		String lastPkg = "";
		for(String imp : importsList) {
			int dot = imp.indexOf('.');
			String pkg = dot == -1 ? imp : imp.substring(0, dot);
			if(!pkg.equals(lastPkg)) {
				sb.append(n);
			}
			lastPkg = pkg;
			sb.append("import ").append(imp).append(";").append(n);
		}
		// Re-add all imports
		if(source.toString().startsWith("package")) {
			addAfterMatch(source, PACKAGE, sb.toString());
		}
		else source.insert(0, sb);
	}

	protected String replaceTextOfMatchGroup(String source, Pattern pattern, Function<MatchResult,String> replaceStrategy) {
		StringBuilder sb = new StringBuilder(source);
		replaceTextOfMatchGroup(sb, pattern, replaceStrategy);
		return sb.toString();
	}

	protected void replaceTextOfMatchGroup(StringBuilder source, Pattern pattern, Function<MatchResult,String> replaceStrategy) {
		Stack<MatchResult> results = new Stack<>();
		String sourceString = source.toString();
		Matcher matcher = pattern.matcher(sourceString);

		while (matcher.find()) {
			results.push(matcher.toMatchResult());
		}
		while (!results.isEmpty()) {
			MatchResult match = results.pop();
			if (match.start() >= 0 && match.end() >= 0) {
				source.replace(match.start(), match.end(), replaceStrategy.apply(match));
			}
		}
	}

	protected void addAfterMatch(StringBuilder source, Pattern pattern, String stringToAdd) {
		addMatch(source, pattern, stringToAdd, true);
	}

	protected void addBeforeMatch(StringBuilder source, Pattern pattern, String stringToAdd) {
		addMatch(source, pattern, stringToAdd, false);
	}

	private static void addMatch(StringBuilder source, Pattern pattern, String stringToAdd, boolean after) {
		Stack<MatchResult> results = new Stack<>();
		String sourceString = source.toString();
		Matcher matcher = pattern.matcher(sourceString);

		while (matcher.find()) {
			results.push(matcher.toMatchResult());
		}
		while (!results.isEmpty()) {
			MatchResult match = results.pop();
			if(after && match.end() >= 0) {
				source.insert(match.end(), stringToAdd);
			}
			else if (match.start() >= 0) {
				source.insert(match.start(), stringToAdd);
			}
		}
	}
}
