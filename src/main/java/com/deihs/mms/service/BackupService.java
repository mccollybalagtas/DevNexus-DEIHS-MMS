package com.deihs.mms.service;

import com.deihs.mms.config.AppConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/**
 * One-click database backup and restore for administrators.
 *
 * <p>The application talks to MySQL/MariaDB through JDBC, but a logical dump
 * is still the safest portable copy of the data. This class shells out to the
 * {@code mysqldump} and {@code mysql} command-line clients that ship with
 * XAMPP (or any MySQL/MariaDB install), locating them on the PATH and in the
 * usual XAMPP directories. Passwords are passed through {@code MYSQL_PWD}
 * rather than on the command line so they do not appear in the process list.</p>
 */
public final class BackupService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final Preferences PREFS =
            Preferences.userNodeForPackage(BackupService.class);
    private static final String LAST_BACKUP = "lastBackup";

    /** Raised with a message that is safe to show to the user. */
    public static class BackupException extends Exception {
        public BackupException(String message) {
            super(message);
        }

        public BackupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A tiny listener so the UI can show progress without knowing the process. */
    public interface Progress {
        void step(String message);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Folder offered first in the save dialog. */
    public static Path defaultFolder() {
        Path home = Path.of(System.getProperty("user.home"), "DEIHS-MMS", "backups");
        try {
            Files.createDirectories(home);
        } catch (IOException ignored) {
            return Path.of(System.getProperty("user.home"));
        }
        return home;
    }

    /** Suggested file name for a new backup. */
    public static String suggestedFileName() {
        return "deihs-mms-backup-" + LocalDateTime.now().format(STAMP) + ".sql";
    }

    public static String lastBackupAt() {
        return PREFS.get(LAST_BACKUP, "");
    }

    public static void rememberBackup(Path file) {
        PREFS.put(LAST_BACKUP, LocalDateTime.now().format(STAMP) + "|" + file.toAbsolutePath());
    }

    public static Path lastBackupPath() {
        String last = lastBackupAt();
        if (last == null || last.isBlank()) return null;
        int split = last.indexOf('|');
        String path = split < 0 ? "" : last.substring(split + 1).trim();
        if (path.isBlank()) return null;
        return Path.of(path);
    }

    public static LocalDateTime lastBackupTime() {
        String last = lastBackupAt();
        if (last == null || last.isBlank()) return null;
        int split = last.indexOf('|');
        String stamp = split < 0 ? last : last.substring(0, split);
        try {
            return LocalDateTime.parse(stamp, STAMP);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when no backup exists or the last backup is older than maxDays. */
    public static boolean isBackupStale(int maxDays) {
        LocalDateTime t = lastBackupTime();
        if (t == null) return true;
        return t.isBefore(LocalDateTime.now().minusDays(Math.max(1, maxDays)));
    }

    public static String backupHealthMessage(int maxDays) {
        LocalDateTime t = lastBackupTime();
        Path p = lastBackupPath();
        if (t == null) {
            return "WARNING: No database backup has been recorded on this PC.";
        }
        String age = ageLabel(t);
        if (p != null && !Files.exists(p)) {
            return "WARNING: Last backup was " + age + ", but the file is missing: " + p;
        }
        if (p != null && Files.exists(p)) {
            try {
                if (Files.size(p) < 64) {
                    return "WARNING: Last backup file looks too small: " + p;
                }
            } catch (IOException ignored) { }
        }
        if (isBackupStale(maxDays)) {
            return "WARNING: Last backup is stale (" + age + "). Create a fresh backup.";
        }
        return "Backup OK: last backup " + age + (p == null ? "" : " (" + p.getFileName() + ")");
    }

    public static String verifyLastBackup() {
        LocalDateTime t = lastBackupTime();
        Path p = lastBackupPath();
        if (t == null || p == null) {
            return "No backup file recorded. Create a backup first.";
        }
        if (!Files.exists(p)) {
            return "Backup file is missing: " + p;
        }
        if (!Files.isReadable(p)) {
            return "Backup file is not readable: " + p;
        }
        try {
            long size = Files.size(p);
            if (size < 64) return "Backup file is suspiciously small (" + size + " bytes): " + p;
            String head = Files.readString(p, StandardCharsets.UTF_8).substring(0, (int) Math.min(512, size));
            boolean looksSql = head.contains("--") || head.toLowerCase().contains("create") || head.toLowerCase().contains("insert") || head.toLowerCase().contains("drop");
            if (!looksSql) return "Backup file does not look like a SQL dump: " + p;
            return "Backup verified: " + p.getFileName() + " (" + size + " bytes, " + ageLabel(t) + ").";
        } catch (IOException e) {
            return "Could not verify backup: " + e.getMessage();
        }
    }

    private static String ageLabel(LocalDateTime t) {
        long minutes = java.time.Duration.between(t, LocalDateTime.now()).toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " minute(s) ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hour(s) ago";
        long days = hours / 24;
        return days + " day(s) ago";
    }

    /**
     * Writes a full logical dump of the configured database to {@code target}.
     * Blocks; call from a background thread.
     */
    public void backup(Path target, Progress progress) throws BackupException {
        String dump = locate("mysqldump");
        List<String> command = new ArrayList<>();
        command.add(dump);
        command.add("--host=" + AppConfig.get("db.host", "localhost"));
        command.add("--port=" + AppConfig.getInt("db.port", 3306));
        command.add("--user=" + AppConfig.get("db.user", "root"));
        command.add("--protocol=TCP");
        command.add("--default-character-set=utf8mb4");
        command.add("--single-transaction");
        command.add("--quick");
        command.add("--routines");
        command.add("--triggers");
        command.add("--add-drop-table");
        command.add(AppConfig.get("db.name", "deihs_mms"));

        if (progress != null) {
            progress.step("Writing " + target.getFileName() + " …");
        }
        run(command, target.toFile(), null, "backup");
        rememberBackup(target);
        if (progress != null) {
            progress.step("Backup finished.");
        }
    }

    /**
     * Restores a dump into the configured database. This overwrites the current
     * data; the caller must confirm with the administrator first.
     */
    public void restore(Path source, Progress progress) throws BackupException {
        if (source == null || !Files.isReadable(source)) {
            throw new BackupException("The selected backup file cannot be read.");
        }
        String client = locate("mysql");
        List<String> command = new ArrayList<>();
        command.add(client);
        command.add("--host=" + AppConfig.get("db.host", "localhost"));
        command.add("--port=" + AppConfig.getInt("db.port", 3306));
        command.add("--user=" + AppConfig.get("db.user", "root"));
        command.add("--protocol=TCP");
        command.add("--default-character-set=utf8mb4");
        command.add(AppConfig.get("db.name", "deihs_mms"));

        if (progress != null) {
            progress.step("Restoring " + source.getFileName() + " …");
        }
        run(command, null, source.toFile(), "restore");
        if (progress != null) {
            progress.step("Restore finished. Sign in again to reload the data.");
        }
    }

    // ------------------------------------------------------------------
    // Process plumbing
    // ------------------------------------------------------------------

    private void run(List<String> command, File stdoutFile, File stdinFile, String what)
            throws BackupException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        if (stdoutFile != null) {
            builder.redirectOutput(stdoutFile);
        }
        if (stdinFile != null) {
            builder.redirectInput(stdinFile);
        }
        String password = AppConfig.get("db.password", "");
        if (!password.isEmpty()) {
            builder.environment().put("MYSQL_PWD", password);
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new BackupException("Could not start the MySQL client: " + e.getMessage(), e);
        }

        StringBuilder error = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.toLowerCase(Locale.ROOT).contains("using a password")) {
                        continue; // MySQL's "password on the command line" warning.
                    }
                    if (error.length() < 4000) {
                        error.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // The process already failed; the exit code carries the verdict.
            }
        }, "deihs-" + what + "-stderr");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new BackupException("The " + what + " took too long and was stopped.");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BackupException("The " + what + " was interrupted.", e);
        }

        int exit = process.exitValue();
        if (exit != 0) {
            String detail = error.toString().trim();
            if (detail.isEmpty()) {
                detail = "the client exited with code " + exit;
            }
            throw new BackupException("The " + what + " failed: " + detail);
        }
    }

    // ------------------------------------------------------------------
    // Locating the clients
    // ------------------------------------------------------------------

    private static String locate(String base) throws BackupException {
        String override = System.getProperty("deihs." + base);
        if (override == null || override.isBlank()) {
            override = System.getenv("DEIHS_" + base.toUpperCase(Locale.ROOT));
        }
        if (override != null && !override.isBlank()) {
            File file = new File(override);
            if (file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        String exe = isWindows() ? base + ".exe" : base;
        List<String> candidates = new ArrayList<>();
        for (String dir : searchFolders()) {
            candidates.add(new File(dir, exe).getAbsolutePath());
        }
        candidates.add(exe); // PATH

        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.canExecute() || (!candidate.contains(File.separator) && onPath(candidate))) {
                return candidate;
            }
        }
        throw new BackupException(
                "Could not find the '" + base + "' client. Install MySQL/MariaDB or set "
                        + "-Ddeihs." + base + "=C:\\path\\to\\" + exe + " when launching.");
    }

    private static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File file = new File(dir, command);
            if (file.canExecute()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> searchFolders() {
        List<String> dirs = new ArrayList<>();
        if (isWindows()) {
            String pf = System.getenv("ProgramFiles");
            String pf86 = System.getenv("ProgramFiles(x86)");
            dirs.add("C:\\xampp\\mysql\\bin");
            dirs.add("C:\\XAMPP\\mysql\\bin");
            if (pf != null) {
                dirs.add(pf + "\\MySQL\\MySQL Server 8.0\\bin");
                dirs.add(pf + "\\MySQL\\MySQL Server 8.4\\bin");
                dirs.add(pf + "\\MariaDB 11.4\\bin");
                dirs.add(pf + "\\MariaDB 10.11\\bin");
            }
            if (pf86 != null) {
                dirs.add(pf86 + "\\MySQL\\MySQL Server 8.0\\bin");
            }
        } else {
            dirs.add("/opt/lampp/bin");
            dirs.add("/usr/bin");
            dirs.add("/usr/local/bin");
            dirs.add("/usr/local/mysql/bin");
            dirs.add("/usr/sbin");
            dirs.add("/opt/homebrew/bin");
            dirs.add("/Applications/XAMPP/xamppfiles/bin");
        }
        return dirs;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
