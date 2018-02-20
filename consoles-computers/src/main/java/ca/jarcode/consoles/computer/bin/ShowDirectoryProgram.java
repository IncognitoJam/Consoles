package ca.jarcode.consoles.computer.bin;

import ca.jarcode.consoles.computer.Computer;
import ca.jarcode.consoles.computer.Terminal;
import ca.jarcode.consoles.computer.filesystem.FSBlock;
import ca.jarcode.consoles.computer.filesystem.FSFolder;
import ca.jarcode.consoles.computer.filesystem.FSGroup;
import ca.jarcode.consoles.computer.filesystem.FSProvidedProgram;
import ca.jarcode.consoles.computer.manual.ProvidedManual;
import org.bukkit.ChatColor;

import java.util.Map;

@ProvidedManual(
        author = "Jarcode",
        version = "1.4",
        contents = "Shows the current directory of the terminal instance, and will change the " +
                "current directory when a valid path is passed to this program."
)
public class ShowDirectoryProgram extends FSProvidedProgram {

    public ShowDirectoryProgram() {
        setExecutable(FSGroup.ALL, true);
    }

    @Override
    public void run(String str, Computer computer) throws Exception {
        str = handleEncapsulation(str.trim());
        Terminal terminal = computer.getTerminal(this);
        String cd = terminal.getCurrentDirectory();
        FSBlock block = computer.getBlock(str, cd);
        if (!(block instanceof FSFolder)) {
            println(str + ": invalid path");
            return;
        }
        FSFolder folder = (FSFolder) block;
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (Map.Entry<String, FSBlock> entry : folder.contents.entrySet()) {
            if (entry.getValue() instanceof FSFolder)
                builder.append(ChatColor.BLUE);
            else if (entry.getValue() instanceof FSProvidedProgram)
                builder.append(ChatColor.RED);
            else
                builder.append(ChatColor.WHITE);
            builder.append(entry.getKey());
            if (index != folder.contents.size() - 1)
                builder.append("\t");
        }
        println(builder.toString());
    }

}
