package ca.jarcode.consoles.computer;

import ca.jarcode.consoles.computer.filesystem.*;
import ca.jarcode.consoles.computer.interpreter.SandboxProgram;
import ca.jarcode.consoles.internal.ConsoleFeed;
import org.bukkit.ChatColor;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

/*

For our ConsoleFeed, this is what handles the prompt for running
programs.

If you want to take this and turn it into a mini SH/Bash emulator,
go ahead.

 */
public class ProgramCreator implements ConsoleFeed.FeedCreator {

    private String result = null;
    private ProgramInstance lastInstance;
    private final Terminal terminal;

    public ProgramCreator(Terminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public void from(String input) {

        String absPath, argument;

        // string encapsulation
        if (input.startsWith("\"")) {

            StringBuilder builder = new StringBuilder();

            char[] arr = input.toCharArray();
            for (int t = 1; t < builder.length(); t++) {
                if (arr[t] != '\"' && (t == 1 || arr[t - 1] != '\\')) {
                    builder.append(arr[t]);
                } else break;
            }
            if (input.length() > builder.length())
                argument = input.substring(builder.length() + 1);
            else
                argument = "";
            input = builder.toString();
        } else {
            String[] split = input.split(" ");
            if (split.length > 1) {
                argument = input.substring(split[0].length() + 1);
            } else argument = "";
            input = split[0];
        }

        FSBlock block;
        String old = input;
        // start from root
        if (input.startsWith("/")) {
            input = input.substring(1);
            try {
                absPath = old;
                block = terminal.getComputer().getRoot().get(input);
            } catch (FileNotFoundException e) {
                result = old + ": program not found";
                return;
            }
        }
        // system path or current dir
        else check:if (!input.contains("/")) {

            // cd
            block = terminal.getComputer().getBlock(input, terminal.getCurrentDirectory());
            if (block != null) {
                String cd = terminal.getCurrentDirectory();
                String p = cd.endsWith("/") ? cd.substring(0, cd.length() - 1) : cd;
                absPath = p + "/" + input;
                break check;
            }
            // loop through path entries if no match in current directory
            for (String path : terminal.getComputer().getSystemPath()) {
                try {
                    FSBlock pathBlock = terminal.getComputer().getRoot().get(path);
                    if (pathBlock instanceof FSFolder) {
                        FSFolder folder = (FSFolder) pathBlock;
                        try {
                            absPath = "/" + path + "/" + input;
                            block = folder.get(input);
                            break check;
                        }
                        // program doesn't exist in this path entry, try another
                        catch (FileNotFoundException ignored) {
                        }
                    } else {
                        result = old + ": system path entry does not point to a file (" + path + ")";
                        return;
                    }
                }
                // invalid path variable
                catch (FileNotFoundException ignored) {
                    result = old + ": invalid system path";
                    return;
                }
            }
            result = old + ": program not found";
            return;
        }
        // start from current directory
        else {
            try {
                block = terminal.getComputer().getRoot().get(terminal.getCurrentDirectory());
                try {
                    block = ((FSFolder) block).get(input);
                    if (!(block instanceof FSFile || block instanceof FSProvidedProgram)) {
                        result = "invalid path: must be a file or provided program";
                        return;
                    } else {
                        String cd = terminal.getCurrentDirectory();
                        String p = cd.endsWith("/") ? cd.substring(0, cd.length() - 1) : cd;
                        absPath = p + "/" + input;
                    }
                } catch (FileNotFoundException e) {
                    result = old + ": program not found";
                    return;
                }
            } catch (FileNotFoundException e) {
                result = old + ": program not found";
                return;
            }
        }
        // try running the program
        result = tryBlock(block, argument, terminal.getUser(), absPath);
    }

    @Override
    public String result() {
        return result;
    }

    @Override
    public InputStream getInputStream() {
        return lastInstance.in;
    }

    @Override
    public OutputStream getOutputStream() {
        return lastInstance.out;
    }

    @Override
    public ConsoleFeed.FeedEncoder getEncoder() {
        return ConsoleFeed.UTF_ENCODER;
    }

    public ProgramInstance getLastInstance() {
        return lastInstance;
    }

    public void setCurrentInstance(ProgramInstance instance) {
        lastInstance = instance;
    }

    String tryBlock(FSBlock target, String argument, String user, String path) {
        ProgramInstance instance;
        if ((target instanceof FSFile || target instanceof FSProvidedProgram)) {
            if (target.getOwner().equals(user))
                if (!target.check(FSGroup.OWNER, 'x'))
                    return "permission denied";
                else if (!target.check(FSGroup.ALL, 'x'))
                    return "permission denied";
        }
        if (target instanceof FSFile) {
            instance = new ProgramInstance(SandboxProgram.FILE_FACTORY.call((FSFile) target, path), argument, terminal.getComputer());
        } else if (target instanceof FSProvidedProgram) {
            instance = new ProgramInstance((FSProvidedProgram) target, argument, terminal.getComputer());
        } else {
            return "invalid path: must be a file or provided program";
        }
        try {
            instance.startInThread();
        } catch (Throwable e) {
            return ChatColor.RED + "unable to start thread: " + e.getClass().getSimpleName();
        }
        result = null;
        lastInstance = instance;
        return null;
    }

}
