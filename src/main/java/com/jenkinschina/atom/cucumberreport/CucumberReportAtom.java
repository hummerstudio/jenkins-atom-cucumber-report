package com.jenkinschina.atom.cucumberreport;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * cucumber-reports as an atom: read the JSON cucumber writes and summarise it.
 *
 * <p>Scenario-level counts are what a build gates on. The plugin also renders the pretty HTML; this one writes a
 * report of its own so the result shows on the build page without the plugin's assets.
 */
public class CucumberReportAtom implements AtomTask {

    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STEP_STATUS = Pattern.compile("\"status\"\\s*:\\s*\"(passed|failed|skipped|undefined|pending|ambiguous)\"");
    private static final Pattern FEATURE_ID = Pattern.compile("\"uri\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/cucumber.json, **/*cucumber*.json, **/target/cucumber-reports/*.json");
        String label = context.getParam("label", "Cucumber");
        String outDir = context.getParam("outputDir", "out/cucumber");
        boolean failOnFailure = context.getBooleanParam("failOnFailure", false);
        boolean skipMarkingUnstable = context.getBooleanParam("skipMarkingUnstable", false);

        List<Path> files = match(workspace, pattern);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no cucumber json matched " + pattern);
        }

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<Scenario> scenarios = new ArrayList<>();
        for (Path file : files) {
            read(Files.readString(file, StandardCharsets.UTF_8), scenarios, byStatus);
        }
        if (scenarios.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "the files are not cucumber json (no step found)");
        }

        int failed = scenarios.stream().mapToInt(s -> "failed".equals(s.verdict()) ? 1 : 0).sum();
        int passed = scenarios.stream().mapToInt(s -> "passed".equals(s.verdict()) ? 1 : 0).sum();
        int skipped = scenarios.size() - failed - passed;

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"),
                render(label, pattern, scenarios, passed, failed, skipped, byStatus), StandardCharsets.UTF_8);

        context.log("[cucumber] " + scenarios.size() + " scenario(s): " + passed + " passed, " + failed
                + " failed, " + skipped + " skipped");

        AtomResult result = AtomResult.success()
                .data("scenarios", String.valueOf(scenarios.size()))
                .data("passed", String.valueOf(passed))
                .data("failed", String.valueOf(failed))
                .data("skipped", String.valueOf(skipped))
                .report("report", label, dir.toString(), "index.html");
        if (failed > 0 && !skipMarkingUnstable) {
            result.markUnstable();
        }
        if (failed > 0 && failOnFailure) {
            return AtomResult.failure(failed + " scenario(s) failed")
                    .data("scenarios", String.valueOf(scenarios.size()))
                    .data("failed", String.valueOf(failed))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    /**
     * The json is an array of features, each with elements (scenarios) whose steps carry a status. Rather than pull a
     * json library into every atom package, the file is walked by the markers that separate one scenario from the next:
     * the {@code "steps"} key starts a scenario, the {@code "name"} before it is its name.
     */
    private static void read(String json, List<Scenario> scenarios, Map<String, Integer> byStatus) {
        Matcher steps = Pattern.compile("\"steps\"\\s*:").matcher(json);
        List<Integer> starts = new ArrayList<>();
        while (steps.find()) {
            starts.add(steps.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : json.length();
            String block = json.substring(from, to);
            String scenarioName = last(NAME, json.substring(Math.max(0, from - 400), from));
            if (scenarioName.isBlank()) {
                continue;
            }
            String featureName = last(NAME, json.substring(0, Math.max(0, from - 400)));
            String verdict = "passed";
            Matcher status = STEP_STATUS.matcher(block);
            while (status.find()) {
                String value = status.group(1);
                byStatus.merge(value, 1, Integer::sum);
                if ("failed".equals(value) || "ambiguous".equals(value)) {
                    verdict = "failed";
                } else if (!"passed".equals(value) && !"failed".equals(verdict)) {
                    verdict = "skipped";
                }
            }
            scenarios.add(new Scenario(featureName, scenarioName, verdict));
        }
    }

    private static String last(Pattern pattern, String text) {
        String found = "";
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found = matcher.group(1);
        }
        return found;
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<Path> match(Path workspace, String pattern) throws Exception {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.stream().anyMatch(m -> m.matches(workspace.relativize(path))
                            || m.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path)))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(200)
                    .forEach(files::add);
        }
        return files;
    }

    private static String render(String label, String pattern, List<Scenario> scenarios, int passed, int failed,
                                 int skipped, Map<String, Integer> byStatus) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(label).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:110px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3}")
                .append("th{background:#fafbfc;color:#6b7280}")
                .append("</style></head><body>");
        html.append("<h1>").append(label).append("</h1><p class=\"meta\">").append(scenarios.size())
                .append(" scenario(s) · <code>").append(pattern).append("</code></p><div class=\"cards\">");
        html.append("<div class=\"card\"><b>").append(passed).append("</b><span>passed</span></div>");
        html.append("<div class=\"card\"><b>").append(failed).append("</b><span>failed</span></div>");
        html.append("<div class=\"card\"><b>").append(skipped).append("</b><span>skipped</span></div></div>");
        html.append("<table><tr><th>Feature</th><th>Scenario</th><th>Verdict</th></tr>");
        for (Scenario scenario : scenarios) {
            html.append("<tr><td>").append(scenario.feature()).append("</td><td>").append(scenario.name())
                    .append("</td><td>").append(scenario.verdict()).append("</td></tr>");
        }
        html.append("</table><p class=\"meta\">steps: ").append(byStatus).append("</p>");
        return html.append("</body></html>").toString();
    }

    private record Scenario(String feature, String name, String verdict) {}
}
