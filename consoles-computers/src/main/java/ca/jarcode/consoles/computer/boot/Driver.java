package ca.jarcode.consoles.computer.boot;

// OK, drivers on this system are interesting.

// We have devices that anything can read from and handle, which is great for allowing low-level access to any
// application willing to handle I/O for drivers, but it also means the kernel itself needs something to read from it!

// We do this in the main server thread, checking for input from the devices each tick and then handling the input
// accordingly. DO NOT USE BLOCKING INPUT!!

import ca.jarcode.consoles.computer.Computer;
import ca.jarcode.consoles.computer.filesystem.FSFile;

// Also, there is one driver instance per file.
public abstract class Driver {

    private FSFile device;
    protected final Computer computer;

    public Driver(FSFile device, Computer computer) {
        this.device = device;
        this.computer = computer;
    }

    public FSFile getDevice() {
        return device;
    }

    public abstract void tick();

    public abstract void stop();

}
