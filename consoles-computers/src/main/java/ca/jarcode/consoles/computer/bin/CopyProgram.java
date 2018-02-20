package ca.jarcode.consoles.computer.bin;

import ca.jarcode.consoles.computer.Computer;
import ca.jarcode.consoles.computer.Terminal;
import ca.jarcode.consoles.computer.filesystem.FSBlock;
import ca.jarcode.consoles.computer.filesystem.FSFolder;
import ca.jarcode.consoles.computer.filesystem.FSProvidedProgram;
import ca.jarcode.consoles.computer.filesystem.FSStoredFile;
import ca.jarcode.consoles.computer.manual.ProvidedManual;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static ca.jarcode.consoles.computer.ProgramUtils.splitArguments;

@ProvidedManual(
        author = "Jarcode",
        version = "1.6",
        contents = "Copies a file or folder from one path location to another."
)
public class CopyProgram extends FSProvidedProgram {

    @Override
    public void run(String in, Computer computer) throws Exception {
        String[] args = splitArguments(in);
        if (args.length < 2 || in.trim().isEmpty()) {
            printUsage();
            return;
        }
        Terminal terminal = computer.getTerminal(this);
        FSBlock sourceBlock = computer.getBlock(args[0], terminal.getCurrentDirectory());
        FSBlock targetBlock = computer.getBlock(args[1], terminal.getCurrentDirectory());
        if (sourceBlock == null) {
            print("cp: " + args[0].trim() + ": file does not exist");
            return;
        }
        if (targetBlock != null) {
            print("cp: " + args[1].trim() + ": file exists");
            return;
        }
        String[] arr = FSBlock.section(args[0], "/");
        String sourceBase = Arrays.stream(arr)
                .limit(arr.length == 0 ? 0 : arr.length - 1)
                .collect(Collectors.joining("/"));
        if (sourceBase.trim().isEmpty() && args[0].startsWith("/"))
            sourceBase = "/";
        String sourceFile = Arrays.stream(arr)
                .filter(s -> !s.isEmpty())
                .reduce((o1, o2) -> o2)
                .orElse(null);
        arr = FSBlock.section(args[1], "/");
        String targetBase = Arrays.stream(arr)
                .limit(arr.length == 0 ? 0 : arr.length - 1)
                .collect(Collectors.joining("/"));
        if (targetBase.trim().isEmpty() && args[1].startsWith("/"))
            targetBase = "/";
        String targetFile = Arrays.stream(arr)
                .filter(s -> !s.isEmpty())
                .reduce((o1, o2) -> o2)
                .orElse(null);
        if (!FSBlock.allowedBlockName(sourceFile)) {
            print("cp: " + String.valueOf(sourceFile).trim() + ": bad block name");
            return;
        }
        if (!FSBlock.allowedBlockName(targetFile)) {
            print("cp: " + String.valueOf(targetFile) + ": bad block name");
            return;
        }
        FSBlock sourceBaseFolder = computer.getBlock(sourceBase, terminal.getCurrentDirectory());
        if (sourceBaseFolder == null) {
            print("cp: " + sourceBase.trim() + ": does not exist");
            return;
        }
        FSBlock targetBaseFolder = computer.getBlock(targetBase, terminal.getCurrentDirectory());
        if (targetBaseFolder == null) {
            print("cp: " + targetBase.trim() + ": does not exist");
            return;
        }
        if (!(targetBaseFolder instanceof FSFolder)) {
            print("cp: " + targetBase + ": not a folder");
            return;
        }
        FSFolder b = ((FSFolder) sourceBaseFolder);
        FSFolder k = ((FSFolder) targetBaseFolder);
        FSBlock a = b.contents.get(sourceFile);
        k.contents.put(targetFile, copy(a, computer));
    }

    private FSStoredFile fileCopy(FSStoredFile file, Computer computer) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Charset charset = Charset.forName("UTF-8");
        try (InputStream is = file.createInput()) {
            int i;
            while (true) {
                if (terminated())
                    break;
                if (is.available() > 0 || is instanceof ByteArrayInputStream) {
                    i = is.read();
                    if (i == -1) break;
                    out.write((byte) i);
                } else {
                    Thread.sleep(50);
                }
            }
            if (terminated())
                print("\tterminated");
        }
        return writtenFile(new String(out.toByteArray(), charset), computer);

    }

    private FSStoredFile writtenFile(String text, Computer computer) throws IOException {
        FSStoredFile file = new FSStoredFile(computer);
        OutputStream out = file.createOutput();
        out.write(text.getBytes(Charset.forName("UTF-8")));
        out.close();
        return file;
    }

    private FSBlock copy(FSBlock blk, Computer computer) throws IOException, InterruptedException {
        if (blk instanceof FSStoredFile) {
            return fileCopy((FSStoredFile) blk, computer);
        } else if (blk instanceof FSFolder) {
            FSFolder a = (FSFolder) blk;
            FSFolder folder = new FSFolder();
            for (Map.Entry<String, FSBlock> entry : a.contents.entrySet()) {
                if (terminated())
                    break;
                FSBlock block = copy(entry.getValue(), computer);
                if (block != null)
                    folder.contents.put(entry.getKey(), block);
            }
            return folder;
        } else if (blk instanceof FSProvidedProgram) {
            return blk;
        } else return null;
    }

    private void printUsage() {
        println("Usage: cp [SOURCE] [TARGET] ");
    }

}
