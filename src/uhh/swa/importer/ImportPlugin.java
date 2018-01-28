package uhh.swa.importer;

import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;

public class ImportPlugin extends Plugin {
    @Override
    public PluginAction[] getActions() {
        return new PluginAction[]{new ImportAction(this)};
    }
}
