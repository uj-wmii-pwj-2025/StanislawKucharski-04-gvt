package uj.wmii.pwj.gvt;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;


public class Gvt {
    private final ExitHandler exitHandler;
    private final FileRepository repo;
    private final CommandFactory factory;

    public Gvt(ExitHandler exitHandler) {
        this.exitHandler = exitHandler;
        this.repo = new FileRepository(exitHandler);
        this.factory = new CommandFactory(repo, exitHandler);
    }

    public static void main(String... args) {
        new Gvt(new ExitHandler()).mainInternal(args);
    }

    public void mainInternal(String... args) {
        if (args.length == 0) {
            exitHandler.exit(1, "Please specify command.");
            return;
        }

        String commandName = args[0].toUpperCase();
        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);

        GvtCommand command = factory.getCommand(commandName);
        if (command == null) {
            exitHandler.exit(1, "Unknown command " + args[0] + ".");
            return;
        }

        command.execute(commandArgs);
    }


    interface GvtCommand {
        void execute(String[] args);
    }

    static class CommandFactory {
        private final Map<String, GvtCommand> commands = new HashMap<>();

        public CommandFactory(FileRepository repo, ExitHandler exitHandler) {
            commands.put("INIT", new InitCommand(repo, exitHandler));
            commands.put("ADD", new AddCommand(repo, exitHandler));
            commands.put("DETACH", new DetachCommand(repo, exitHandler));
            commands.put("CHECKOUT", new CheckoutCommand(repo, exitHandler));
            commands.put("COMMIT", new CommitCommand(repo, exitHandler));
            commands.put("HISTORY", new HistoryCommand(repo, exitHandler));
            commands.put("VERSION", new VersionCommand(repo, exitHandler));
        }

        public GvtCommand getCommand(String name) {
            return commands.get(name);
        }
    }

    static class FileRepository {
        private final ExitHandler exitHandler;
        private static final String PREFIX = ".gvt";
        private static final String MESSAGE_FILE_NAME = ".gvt.message";
        private static final String ACTIVE_FILE_NAME = ".gvt.active";
        private static final String LATEST_FILE_NAME = ".gvt.latest";

        public FileRepository(ExitHandler exitHandler) {
            this.exitHandler = exitHandler;
        }

        public Path getHome() {
            Path home = Paths.get(PREFIX).toAbsolutePath();
            if (!Files.exists(home))
                exitHandler.exit(-2, "Current directory is not initialized. Please use init command to initialize.");
            return home;
        }

        public int getLatestVersion(Path home) throws IOException {
            return Integer.parseInt(Files.readString(home.resolve(LATEST_FILE_NAME)));
        }

        public int getActiveVersion(Path home) throws IOException {
            return Integer.parseInt(Files.readString(home.resolve(ACTIVE_FILE_NAME)));
        }

        public void setActiveVersion(Path home, int version) throws IOException {
            Files.writeString(home.resolve(ACTIVE_FILE_NAME), Integer.toString(version));
        }

        public Path createNewVersion(Path home, int latestVersion) throws IOException {
            int newVersion = latestVersion + 1;
            Path newFolder = home.resolve(String.valueOf(newVersion));
            Files.createDirectories(newFolder);
            copyDirectory(home.resolve(String.valueOf(latestVersion)), newFolder);
            return newFolder;
        }

        public void finalizeVersion(Path home, Path versionFolder, String message, int versionNumber) throws IOException {
            Files.writeString(versionFolder.resolve(MESSAGE_FILE_NAME), message);
            Files.writeString(home.resolve(LATEST_FILE_NAME), String.valueOf(versionNumber));
            Files.writeString(home.resolve(ACTIVE_FILE_NAME), String.valueOf(versionNumber));
        }

        public void copyDirectory(Path source, Path target) throws IOException {
            if (!Files.exists(target)) Files.createDirectories(target);
            File[] entries = source.toFile().listFiles();
            if (entries == null) return;
            for (File entry : entries) {
                if (entry.getName().startsWith(PREFIX + ".")) continue;
                Path src = entry.toPath(), dst = target.resolve(entry.getName());
                if (entry.isDirectory()) copyDirectory(src, dst);
                else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        public String getPrefix() { return PREFIX; }
        public String getMessageFileName() { return MESSAGE_FILE_NAME; }
    }


    static class InitCommand implements GvtCommand {
        private final FileRepository repo;
        private final ExitHandler exitHandler;
        public InitCommand(FileRepository repo, ExitHandler exitHandler) {
            this.repo = repo;
            this.exitHandler = exitHandler;
        }

        @Override
        public void execute(String[] args) {
            Path home = Paths.get(".gvt").toAbsolutePath();
            if (Files.exists(home)) {
                exitHandler.exit(10, "Current directory is already initialized.");
                return;
            }

            try {
                Files.createDirectories(home);
                Files.writeString(home.resolve(".gvt.latest"), "0");
                Files.writeString(home.resolve(".gvt.active"), "0");
                Path v0 = home.resolve("0");
                Files.createDirectories(v0);
                Files.writeString(v0.resolve(".gvt.message"), "GVT initialized.");
            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
            }
            exitHandler.exit(0, "Current directory initialized successfully.");
        }
    }

    static class AddCommand implements GvtCommand {
        private final FileRepository repo;
        private final ExitHandler exitHandler;
        public AddCommand(FileRepository repo, ExitHandler exitHandler) {
            this.repo = repo;
            this.exitHandler = exitHandler;
        }

        @Override
        public void execute(String[] args) {
            if (args.length == 0) {
                exitHandler.exit(20, "Please specify file to add.");
                return;
            }

            String filename = args[0];
            String defaultMessage = "File added successfully. File: " + filename;
            String userMessage = (args.length > 2 && "-m".equals(args[1])) ? args[2] : null;

            Path home = repo.getHome();
            Path file = Paths.get(filename).toAbsolutePath();

            try {
                if (!Files.exists(file)) {
                    exitHandler.exit(21, "File not found. File: " + filename);
                    return;
                }

                int latestVersion = repo.getLatestVersion(home);
                Path existing = home.resolve(String.valueOf(latestVersion)).resolve(filename);
                if (Files.exists(existing)) {
                    exitHandler.exit(0, "File already added. File: " + filename);
                    return;
                }

                Path newFolder = repo.createNewVersion(home, latestVersion);
                Path targetFile = newFolder.resolve(filename);
                Files.copy(file, targetFile);

                String commitMessage = (userMessage != null) ? userMessage : defaultMessage;
                repo.finalizeVersion(home, newFolder, commitMessage, latestVersion + 1);

            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(22, "File cannot be added. See ERR for details. File: " + filename);
            }
            exitHandler.exit(0, defaultMessage);
        }
    }

    static class DetachCommand implements GvtCommand {
        private final FileRepository repo;
        private final ExitHandler exitHandler;
        public DetachCommand(FileRepository repo, ExitHandler exitHandler) {
            this.repo = repo;
            this.exitHandler = exitHandler;
        }

        @Override
        public void execute(String[] args) {
            if (args.length == 0) {
                exitHandler.exit(30, "Please specify file to detach.");
                return;
            }

            String filename = args[0];
            String defaultMessage = "File detached successfully. File: " + filename;
            String userMessage = (args.length > 2 && "-m".equals(args[1])) ? args[2] : null;

            Path home = repo.getHome();

            try {
                int latestVersion = repo.getLatestVersion(home);
                Path latestFolder = home.resolve(String.valueOf(latestVersion));
                Path existing = latestFolder.resolve(filename);
                if (!Files.exists(existing)) {
                    exitHandler.exit(0, "File is not added to gvt. File: " + filename);
                    return;
                }

                Path newFolder = repo.createNewVersion(home, latestVersion);
                Files.deleteIfExists(newFolder.resolve(filename));

                String commitMessage = (userMessage != null) ? userMessage : defaultMessage;
                repo.finalizeVersion(home, newFolder, commitMessage, latestVersion + 1);

            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(22, "File cannot be detached. See ERR for details. File: " + filename);
            }
            exitHandler.exit(0, defaultMessage);
        }
    }

    static class CheckoutCommand implements GvtCommand {
        private final FileRepository repo;
        private final ExitHandler exitHandler;
        public CheckoutCommand(FileRepository repo, ExitHandler exitHandler) {
            this.repo = repo;
            this.exitHandler = exitHandler;
        }

        @Override
        public void execute(String[] args) {
            if (args.length == 0) {
                exitHandler.exit(20, "Please specify version to checkout.");
                return;
            }

            int version = Integer.parseInt(args[0]);
            Path home = repo.getHome();

            try {
                int latest = repo.getLatestVersion(home);
                if (version < 0 || version > latest) {
                    exitHandler.exit(60, "Invalid version number: " + version);
                    return;
                }

                Path wantedFolder = home.resolve(String.valueOf(version));
                repo.copyDirectory(wantedFolder, Paths.get(""));
                repo.setActiveVersion(home, version);

            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(9923, "Checkout cannot be completed. See Err for details");
            }
            exitHandler.exit(0, "Checkout successful for version: " + version);
        }
    }

    static class CommitCommand implements GvtCommand {
        private final FileRepository repo;
        private final ExitHandler exitHandler;
        public CommitCommand(FileRepository repo, ExitHandler exitHandler) {
            this.repo = repo;
            this.exitHandler = exitHandler;
        }

        @Override
        public void execute(String[] args) {
            if (args.length == 0) {
                exitHandler.exit(50, "Please specify file to commit.");
                return;
            }

            String filename = args[0];
            String defaultMessage = "File committed successfully. File: " + filename;
            String userMessage = (args.length > 2 && "-m".equals(args[1])) ? args[2] : null;

            Path home = repo.getHome();
            Path file = Paths.get(filename).toAbsolutePath();

            try {
                if (!Files.exists(file)) {
                    exitHandler.exit(51, "File not found. File: " + filename);
                    return;
                }

                int latestVersion = repo.getLatestVersion(home);
                Path latestFolder = home.resolve(String.valueOf(latestVersion));
                Path existing = latestFolder.resolve(filename);
                if (!Files.exists(existing)) {
                    exitHandler.exit(0, "File is not added to gvt. File: " + filename);
                    return;
                }

                Path newFolder = repo.createNewVersion(home, latestVersion);
                Files.deleteIfExists(newFolder.resolve(filename));
                Files.copy(file, newFolder.resolve(filename));

                String commitMessage = (userMessage != null) ? userMessage : defaultMessage;
                repo.finalizeVersion(home, newFolder, commitMessage, latestVersion + 1);

            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(52, "File cannot be committed. See ERR for details. File: " + filename);
            }

            exitHandler.exit(0, defaultMessage);
        }
    }

    static class HistoryCommand implements GvtCommand {
        private final FileRepository repo;
        private final ExitHandler exitHandler;
        public HistoryCommand(FileRepository repo, ExitHandler exitHandler) {
            this.repo = repo;
            this.exitHandler = exitHandler;
        }

        @Override
        public void execute(String[] args) {
            int quantity = -1;
            if (args.length >= 2 && "-last".equals(args[0])) {
                try { quantity = Integer.parseInt(args[1]) - 1; } catch (NumberFormatException ignored) {}
            }

            StringBuilder res = new StringBuilder();
            try {
                Path home = repo.getHome();
                int latestVersion = repo.getLatestVersion(home);
                if (quantity == -1) quantity = latestVersion;

                for (int v = latestVersion; v >= latestVersion - quantity && v >= 0; v--) {
                    Path versionFolder = home.resolve(String.valueOf(v));
                    Path messageFile = versionFolder.resolve(".gvt.message");
                    String message = Files.readString(messageFile).split("\n")[0];
                    res.append(v).append(": ").append(message).append("\n");
                }

            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(9952, "History cannot be read. See ERR for details.");
                return;
            }

            exitHandler.exit(0, res.toString());
        }
    }

    static class VersionCommand implements GvtCommand {
        private final FileRepository repo;
        private final ExitHandler exitHandler;
        public VersionCommand(FileRepository repo, ExitHandler exitHandler) {
            this.repo = repo;
            this.exitHandler = exitHandler;
        }

        @Override
        public void execute(String[] args) {
            Path home = repo.getHome();
            int version;

            try {
                if (args.length > 0) version = Integer.parseInt(args[0]);
                else version = repo.getActiveVersion(home);
            } catch (Exception e) {
                exitHandler.exit(60, "Invalid version number: " + (args.length > 0 ? args[0] : "null"));
                return;
            }

            try {
                int latestVersion = repo.getLatestVersion(home);
                if (version < 0 || version > latestVersion) {
                    exitHandler.exit(60, "Invalid version number: " + version);
                    return;
                }

                Path messageFile = home.resolve(String.valueOf(version)).resolve(".gvt.message");
                String message = Files.readString(messageFile);
                exitHandler.exit(0, "Version: " + version + "\n" + message);

            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(9972, "Version cannot be read. See ERR for details.");
            }
        }
    }
}
