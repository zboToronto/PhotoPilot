package com.photopilot.integration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for ExifTool's -stay_open protocol, run as a real child process by ExifToolProcessTest: reads argument lines
 * from stdin until -executeN, answers on stdout ("args: ...", then {readyN}) and stderr ("err N", then the -echo4 text).
 * Special arguments: -sleep (never answers), -die (exits mid-request), -spam N (N extra stdout and stderr lines).
 * "-stay_open" followed by "False" ends the process.
 */
public final class FakeExifTool {
    public static void main(String[] ignored) throws Exception {
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        var err = new PrintStream(System.err, false, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>();
        String previous = "";
        for (String line; (line = in.readLine()) != null; previous = line) {
            if (previous.equals("-stay_open") && line.equals("False")) { out.flush(); err.flush(); return; }
            if (!line.startsWith("-execute")) { args.add(line); continue; }
            String id = line.substring("-execute".length());
            String echo = "";
            List<String> shown = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equals("-echo4") && i + 1 < args.size()) { echo = args.get(++i); continue; }
                shown.add(args.get(i));
            }
            if (shown.contains("-sleep")) { Thread.sleep(60_000); }
            if (shown.contains("-die")) { System.exit(3); }
            int spam = shown.contains("-spam") ? Integer.parseInt(shown.get(shown.indexOf("-spam") + 1)) : 0;
            for (int i = 0; i < spam; i++) { out.println("line " + i + " of request " + id); err.println("noise " + i + " of request " + id); }
            out.println("args: " + String.join(" | ", shown));
            out.println("{ready" + id + "}");
            err.println("err " + id);
            if (!echo.isEmpty()) err.println(echo);
            out.flush(); err.flush();
            args.clear();
        }
    }
}
