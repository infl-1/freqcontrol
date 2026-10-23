package io.github.freqctl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Runs one command as root through the su binary (Magisk, KernelSU, APatch). */
final class RootShell {
    private static final String[] SU_PATHS = {
            "/debug_ramdisk/su", "/product/bin/su", "/sbin/su", "/system/bin/su", "/system/xbin/su"
    };

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private RootShell() {
    }

    private static String suBinary() {
        for (String path : SU_PATHS) {
            if (new File(path).canExecute()) {
                return path;
            }
        }
        return "su";
    }

    /** Single-quotes a value for use inside an sh command line. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Blocking; call from a background thread. exitCode is -1 on timeout or when su is missing. */
    static Result run(String command, long timeoutMs) {
        try {
            final Process process = new ProcessBuilder(suBinary(), "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final StringBuffer output = new StringBuffer();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
            reader.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, output.toString());
            }
            reader.join(500);
            return new Result(process.exitValue(), output.toString());
        } catch (IOException e) {
            return new Result(-1, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }
}
