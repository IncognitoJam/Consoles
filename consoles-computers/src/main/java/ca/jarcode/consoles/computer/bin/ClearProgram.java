package ca.jarcode.consoles.computer.bin;

import ca.jarcode.consoles.computer.Computer;
import ca.jarcode.consoles.computer.Terminal;
import ca.jarcode.consoles.computer.filesystem.FSProvidedProgram;
import ca.jarcode.consoles.computer.manual.ProvidedManual;

@ProvidedManual(
        author = "Jarcode",
        version = "1.1",
        contents = "Clears the content in the current terminal session"
)
@Deprecated
public class ClearProgram extends FSProvidedProgram {

    @Override
    public void run(String str, Computer computer) throws Exception {
        Terminal terminal = computer.getCurrentTerminal();
        if (terminal != null)
            terminal.clear();
    }

}
