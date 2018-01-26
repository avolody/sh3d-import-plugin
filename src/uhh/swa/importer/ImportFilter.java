package uhh.swa.importer;

import java.io.File;

import javax.swing.filechooser.FileFilter;

public class ImportFilter extends FileFilter {
    @Override
    public boolean accept(File f) {
        return f.isDirectory() || f.getName().toLowerCase().endsWith(".dae");
    }

    @Override
    public String getDescription() {
        //noinspection SpellCheckingInspection
        return "COLLADA file (.dae)";
    }
}
