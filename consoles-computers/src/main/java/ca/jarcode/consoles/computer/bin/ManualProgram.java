package ca.jarcode.consoles.computer.bin;

import ca.jarcode.consoles.computer.Computer;
import ca.jarcode.consoles.computer.filesystem.FSBlock;
import ca.jarcode.consoles.computer.filesystem.FSFile;
import ca.jarcode.consoles.computer.filesystem.FSProvidedProgram;
import ca.jarcode.consoles.computer.manual.ManualEntry;
import ca.jarcode.consoles.computer.manual.ManualManager;
import ca.jarcode.consoles.computer.manual.ProvidedManual;
import org.bukkit.ChatColor;

import java.util.HashMap;
import java.util.Map;

import static ca.jarcode.consoles.computer.ProgramUtils.readFully;
import static ca.jarcode.consoles.computer.ProgramUtils.sleep;

@ProvidedManual(
        author = "Jarcode",
        version = "2.0",
        contents = "A program that displays information necessary for use about functions, programs, and types. This " +
                "program does not contain information about standard lua functions and libraries, see online instead."
)
public class ManualProgram extends FSProvidedProgram {

    @Override
    public void run(String str, Computer computer) throws Exception {
        str = str.trim();
        if (str.isEmpty()) {
            print("usage: man [PROGRAM/FUNCTION/TYPE]");
            return;
        }
        FSBlock foundProgram = null;
        boolean foundRoot = false;

        if (!str.contains("/")) {
            FSBlock block = computer.getBlock(str, "/bin");
            if (block instanceof FSProvidedProgram || block instanceof FSFile) {
                foundProgram = block;
            }
        }
        if (foundProgram == null) {
            FSBlock block = computer.getBlock(str, "/");
            if (block instanceof FSProvidedProgram || block instanceof FSFile) {
                foundProgram = block;
                foundRoot = true;
            }
        }

        ManualEntry programMatch = foundProgram == null ? null : (
                foundProgram instanceof FSProvidedProgram ?
                        ManualManager.PROVIDED_MANUALS.get(foundProgram) : parseFile((FSFile) foundProgram)
        );

        Map<String, ManualEntry> manuals = ManualManager.manuals();

        ManualEntry functionMatch = manuals.get(str);

        // edge case if the user if bringing up a manual for a program in the drive root,
        // make sure to explicitly say that it is from the drive root.
        if (foundRoot && programMatch != null && !str.startsWith("/"))
            str = "/" + str;

        ManualEntry selection;

        if (functionMatch != null && programMatch != null) {
            println("Found multiple manual entries for '" + str + "':\n");
            println(ChatColor.GRAY + "\t\t(function) " + ChatColor.WHITE + functionMatch.getUsage());
            println(ChatColor.GRAY + "\t\t(program) " + ChatColor.WHITE + str);
            print("\nChoose " + ChatColor.RED + "f" + ChatColor.WHITE + " (function) or "
                    + ChatColor.RED + "p" + ChatColor.WHITE + " (program): ");
            String input = read();
            print(input);
            switch (input.trim().toLowerCase()) {
                case "p":
                    selection = programMatch;
                    break;
                case "f":
                    selection = functionMatch;
                    break;
                default:
                    print("\ninvalid response.");
                    return;
            }
        } else if (functionMatch == null && programMatch == null) {
            print("no manual entry for '" + str + "'");
            return;
        } else selection = functionMatch != null ? functionMatch : programMatch;

        sleep(150);

        computer.getTerminal(this).clear();

        println(selection.getText(str));
    }

    private ManualEntry parseFile(FSFile file) {
        String[] lines = readFully(file, this::terminated).split("\n");

        HashMap<String, String> map = new HashMap<>();

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("--#")) {
                line = line.substring(3);
                StringBuilder elementBuilder = new StringBuilder();
                for (char c : line.toCharArray()) {
                    if (c != ' ')
                        elementBuilder.append(c);
                    else break;
                }
                String element = elementBuilder.toString();
                if (!element.trim().isEmpty()) {
                    String data = line.length() > element.length() ? line.substring(element.length() + 1) : "";
                    data = ChatColor.translateAlternateColorCodes('&', data.replace("\\n", "\n").replace("\\t", "\t"));
                    map.put(element, data);
                }
            }
        }
        if (map.isEmpty())
            return null;

        return new ManualEntry((name) -> "Manual for Lua program: " + ChatColor.GREEN + name,
                map.get("author"), map.get("desc"), map.get("version"), map.get("usage"), null);
    }

}
