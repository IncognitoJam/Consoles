package ca.jarcode.consoles.computer.bin;

import ca.jarcode.consoles.computer.Computer;
import ca.jarcode.consoles.computer.filesystem.FSBlock;
import ca.jarcode.consoles.computer.filesystem.FSFile;
import ca.jarcode.consoles.computer.filesystem.FSFolder;
import ca.jarcode.consoles.computer.filesystem.FSProvidedProgram;
import ca.jarcode.consoles.computer.manual.ProvidedManual;
import org.bukkit.ChatColor;

import java.util.stream.Collectors;

@ProvidedManual(
        author = "Jarcode",
        version = "1.6",
        contents = "A program that prints programs installed in the /bin folder, and points " +
                "the user to this project's source code and the 'man' command."
)
@Deprecated
public class HelpProgram extends FSProvidedProgram {

    @Override
    public void run(String str, Computer computer) throws Exception {
        computer.getTerminal(this).clear();
        println("Installed programs: ");
        FSBlock block = resolve("/bin");
        if (block instanceof FSFolder) {
            println(((FSFolder) block).contents.entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof FSProvidedProgram
                            || entry.getValue() instanceof FSFile)
                    .map(entry -> (entry.getValue() instanceof FSProvidedProgram ?
                            ChatColor.RED : ChatColor.YELLOW) + entry.getKey() + ChatColor.RESET)
                    .collect(Collectors.joining("\t")));
        }
        nextln();
        print("Use " + ChatColor.AQUA + "man [program]" + ChatColor.WHITE + " for more information on a program");
        nextln();
        println("Visit " + ChatColor.BLUE + "github.com/IncognitoJam/Consoles/"
                + ChatColor.WHITE + " for source code & support.");
    }

}
