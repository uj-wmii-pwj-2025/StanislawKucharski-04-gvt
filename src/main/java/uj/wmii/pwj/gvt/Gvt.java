package uj.wmii.pwj.gvt;

import java.io.File;
import java.nio.file.*;
import java.io.IOException;

public class Gvt {
    private final String PREFIX = ".gvt";
    private final String MESSAGE_FILE_NAME = ".gvt.message";
    private final String ACTIVE_FILE_NAME = ".gvt.active";
    private final String LATEST_FILE_NAME = ".gvt.latest";
    private final ExitHandler exitHandler;
    public Gvt(ExitHandler exitHandler) {
        this.exitHandler = exitHandler;
    }

    public enum Command{
        INIT {
            @Override
            void execute(Gvt gvt, String[] args) {
                gvt.internal_INIT(args);
            }
        },
        ADD {
            @Override
            void execute(Gvt gvt, String[] args) {
                gvt.internal_ADD(args);
            }
        },
        DETACH {
            @Override
            void execute(Gvt gvt, String[] args) {
                gvt.internal_DETACH(args);
            }
        },
        CHECKOUT {
            @Override
            void execute(Gvt gvt, String[] args) {
                gvt.internal_CHECKOUT(args);
            }
        },
        COMMIT {
            @Override
            void execute(Gvt gvt, String[] args) {
                gvt.internal_COMMIT(args);
            }
        },
        HISTORY {
            @Override
            void execute(Gvt gvt, String[] args) {
                gvt.internal_HISTORY(args);
            }
        },
        VERSION {
            @Override
            void execute(Gvt gvt, String[] args) {
                gvt.internal_VERSION(args);
            }
        };
        abstract void execute(Gvt gvt, String[] args);
    }

    public static void main(String... args) {
        Gvt gvt = new Gvt(new ExitHandler());
        gvt.mainInternal(args);
    }

    public void mainInternal(String... args) {
        if(args.length == 0) {
            exitHandler.exit(1, "Please specify command.");
            return;
        }
        try{
            Command command = Command.valueOf(args[0].toUpperCase());

            String[] commandArgs = new String[args.length - 1];
            if (args.length > 1) {
                System.arraycopy(args, 1, commandArgs, 0, args.length - 1);
            }

            command.execute(this, commandArgs);
        }catch (IllegalArgumentException e){
            exitHandler.exit(1, "Unknown command " + args[0] + ".");
        }
    }

    private void internal_INIT(String[] args) {
        Path home = Paths.get(".gvt").toAbsolutePath();

        if (Files.exists(home)) {
            exitHandler.exit(10, "Current directory is already initialized.");
            return;
        }

        try {
            Files.createDirectories(home);

            Files.writeString(home.resolve(LATEST_FILE_NAME), "0");
            Files.writeString(home.resolve(ACTIVE_FILE_NAME), "0");

            Path v0 = home.resolve("0");
            Files.createDirectories(v0);

            Files.writeString(v0.resolve(MESSAGE_FILE_NAME), "GVT initialized.");

        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
        }
        exitHandler.exit(0, "Current directory initialized successfully.");
    }


    private void internal_ADD(String[] args) {
        if (args.length == 0) {
            exitHandler.exit(20, "Please specify file to add.");
            return;
        }

        String filename = args[0];
        String defaultMessage = "File added successfully. File: " + filename;
        String userMessage = null;
        if (args.length > 2 && "-m".equals(args[1])) {
            userMessage = args[2];
        }

        Path home = getHome();
        Path file = Paths.get(filename).toAbsolutePath();

        try {

            if (!Files.exists(file)) {
                exitHandler.exit(21, "File not found. File: " + filename);
                return;
            }

            int latestVersion = getLatestVersion(home);

            Path existing = home.resolve(String.valueOf(latestVersion)).resolve(filename);
            if (Files.exists(existing)) {
                exitHandler.exit(0, "File already added. File: " + filename);
                return;
            }

            Path newFolder = createNewVersion(home, latestVersion);

            Path targetFile = newFolder.resolve(filename);
            Files.copy(file, targetFile);

            String commitMessage = defaultMessage;
            if (userMessage != null)
                commitMessage = userMessage ;//+ "\n" + defaultMessage;

            finalizeVersion(home, newFolder, commitMessage, latestVersion+1);


        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandler.exit(22, "File cannot be added. See ERR for details. File: " + filename);
        }
        exitHandler.exit(0, defaultMessage);
    }

    private void internal_DETACH(String[] args){
        if (args.length == 0) {
            exitHandler.exit(30, "Please specify file to detach.");
            return;
        }

        String filename = args[0];
        String defaultMessage = "File detached successfully. File: " + filename;
        String userMessage = null;
        if (args.length > 2 && "-m".equals(args[1])) {
            userMessage = args[2];
        }

        Path home = getHome();

        try {

            int latestVersion = getLatestVersion(home);

            Path latestFolder = home.resolve(String.valueOf(latestVersion));
            Path existing = latestFolder.resolve(filename);
            if (!Files.exists(existing)) {
                exitHandler.exit(0, "File is not added to gvt. File: " + filename);
                return;
            }

            Path newFolder = createNewVersion(home, latestVersion);

            Path targetFile = newFolder.resolve(filename);
            Files.delete(targetFile);

            String commitMessage = defaultMessage;
            if (userMessage != null)
                commitMessage = userMessage ;//+ "\n" + defaultMessage;

            finalizeVersion(home, newFolder, commitMessage, latestVersion+1);

        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandler.exit(22, "File cannot be added. See ERR for details. File: " + filename);
        }
        exitHandler.exit(0, defaultMessage);
    }

    private void internal_CHECKOUT(String[] args){
        if (args.length == 0) {
            exitHandler.exit(20, "Please specify version to checkout.");
            return;
        }
        int version = Integer.parseInt(args[0]);
        Path home = getHome();

        try{
            int latest = getLatestVersion(home);
            if(version < 0 || version > latest){
                exitHandler.exit(60, "Invalid version number: " + version);
                return;
            }
            Path wantedFolder = home.resolve(String.valueOf(version));
            copyDirectory(wantedFolder, Paths.get(""));

            setActiveVersion(home, version);

        }catch (IOException e){
            e.printStackTrace(System.err);
            exitHandler.exit(9923, "Checkout cannot be completed. See Err for details");
        }
        exitHandler.exit(0, "Checkout successful for version: " + version);
    }

    private void internal_COMMIT(String[] args){
        if (args.length == 0) {
            exitHandler.exit(50, "Please specify file to commit.");
            return;
        }

        String filename = args[0];
        String defaultMessage = "File committed successfully. File: " + filename;
        String userMessage = null;
        if (args.length > 2 && "-m".equals(args[1])) {
            userMessage = args[2];
        }

        Path home = getHome();
        Path file = Paths.get(filename).toAbsolutePath();

        try {
            if (!Files.exists(file)) {
                exitHandler.exit(51, "File not found. File: " + filename);
                return;
            }

            int latestVersion = getLatestVersion(home);

            Path latestFolder = home.resolve(String.valueOf(latestVersion));
            Path existing = latestFolder.resolve(filename);
            if (!Files.exists(existing)) {
                exitHandler.exit(0, "File is not added to gvt. File: " + filename);
                return;
            }

            Path newFolder = createNewVersion(home, latestVersion);

            Path targetFile = newFolder.resolve(filename);
            Files.delete(targetFile);

            Files.copy(file, targetFile);

            String commitMessage = defaultMessage;
            if (userMessage != null)
                commitMessage = userMessage ;//+ "\n" + defaultMessage;

            finalizeVersion(home, newFolder, commitMessage, latestVersion+1);


        }catch (IOException e){
            e.printStackTrace(System.err);
            exitHandler.exit(52, "File cannot be commited, see ERR for details. File: " + filename);
        }

        exitHandler.exit(0, defaultMessage);
    }

    private void internal_HISTORY(String[] args){
        int quantity = -1;

        if (args.length >= 2 && "-last".equals(args[0])) {
            try {
                quantity = Integer.parseInt(args[1]) - 1;
            } catch (NumberFormatException e) {
                quantity = -1;
            }
        }

        StringBuilder res = new StringBuilder();
        try {
            Path home = getHome();
            int latestVersion = getLatestVersion(home);

            if (quantity == -1)quantity = latestVersion;

            for (int v = latestVersion; v >= latestVersion - quantity; v--) {
                Path versionFolder = home.resolve(String.valueOf(v));
                Path messageFile = versionFolder.resolve(PREFIX + ".message");

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

    private void internal_VERSION(String[] args) {
        Path home = getHome();
        int version;

        if (args.length > 0) {
            try {
                version = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                exitHandler.exit(60, "Invalid version number: " + args[0]);
                return;
            }
        } else {
            try {
                version = getActiveVersion(home);
            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(9972, "Active version cannot be read. See ERR for details.");
                return;
            }
        }

        try {
            int latestVersion = getLatestVersion(home);
            if (version < 0 || version > latestVersion) {
                exitHandler.exit(60, "Invalid version number: " + version);
                return;
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandler.exit(9972, "Cannot read latest version. See ERR for details.");
            return;
        }

        try {
            Path messageFile = home.resolve(String.valueOf(version)).resolve(PREFIX + ".message");
            String message = Files.readString(messageFile);
            exitHandler.exit(0, "Version: " + version + "\n" + message);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandler.exit(9972, "Version cannot be read. See ERR for details.");
        }
    }


    private Path getHome() {
        Path home = Paths.get(".gvt").toAbsolutePath();
        if (!Files.exists(home)) {
            exitHandler.exit(-2, "Current directory is not initialized. Please use init command to initialize.");
        }
        return home;
    }

    private int getLatestVersion(Path home) throws IOException {
        return Integer.parseInt(Files.readString(home.resolve(LATEST_FILE_NAME)));
    }
    private int getActiveVersion(Path home) throws IOException {
        return Integer.parseInt(Files.readString(home.resolve(ACTIVE_FILE_NAME)));
    }
    private void setActiveVersion(Path home, int version) throws IOException {
        Files.writeString(home.resolve(ACTIVE_FILE_NAME), Integer.toString(version));
    }
    private void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        File[] entries = source.toFile().listFiles();
        if (entries == null) return;

        for (File entry : entries) {
            Path sourcePath = entry.toPath();
            Path targetPath = target.resolve(entry.getName());

            if(entry.getName().startsWith(PREFIX+".")) continue;

            if (entry.isDirectory()) {
                copyDirectory(sourcePath, targetPath);
            } else {
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path createNewVersion(Path home, int latestVersion) throws IOException {
        int newVersion = latestVersion + 1;
        Path newFolder = home.resolve(String.valueOf(newVersion));
        Files.createDirectories(newFolder);

        Path latestFolder = home.resolve(String.valueOf(latestVersion));
        copyDirectory(latestFolder, newFolder);

        return newFolder;
    }

    private void finalizeVersion(Path home, Path versionFolder, String message, int versionNumber) throws IOException {
        Files.writeString(versionFolder.resolve(MESSAGE_FILE_NAME), message);
        Files.writeString(home.resolve(LATEST_FILE_NAME), String.valueOf(versionNumber));
        Files.writeString(home.resolve(ACTIVE_FILE_NAME), String.valueOf(versionNumber));
    }
    private String getMessage(Path home, int versionNumber ) throws IOException{
        Path message = home.resolve(String.valueOf(versionNumber)).resolve(MESSAGE_FILE_NAME);
        return Files.readString(message);
    }


}
