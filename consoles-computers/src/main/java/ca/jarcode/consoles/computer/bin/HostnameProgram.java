package ca.jarcode.consoles.computer.bin;

import ca.jarcode.consoles.computer.Computer;
import ca.jarcode.consoles.computer.filesystem.FSBlock;
import ca.jarcode.consoles.computer.filesystem.FSProvidedProgram;
import ca.jarcode.consoles.computer.manual.ProvidedManual;

import static ca.jarcode.consoles.computer.ProgramUtils.schedule;

@ProvidedManual(
        author = "Jarcode",
        version = "1.2",
        contents = "A script that is capable of either printing or changing the hostname of a computer."
)
public class HostnameProgram extends FSProvidedProgram {

    @Override
    public void run(String str, Computer computer) throws Exception {
        if (str.trim().isEmpty()) {
            print(computer.getHostname());
        } else if (FSBlock.allowedBlockName(str)) {
            String hostname = schedule(() -> {
                if (computer.setHostname(str.toLowerCase())) {
                    return str.toLowerCase();
                } else return null;
            }, this::terminated);
            if (hostname != null)
                print("hostname changed to: '" + hostname + '\'');
            else
                print("invalid or taken hostname: '" + str + '\'');
        } else {
            print("illegal hostname: '" + str + '\'');
        }
    }

}
