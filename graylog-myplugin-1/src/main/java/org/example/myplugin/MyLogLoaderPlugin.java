package org.example.myplugin;

import com.google.auto.service.AutoService;
import org.graylog2.plugin.Plugin;
import org.graylog2.plugin.PluginMetaData;

import java.util.Collection;
import java.util.Collections;

@AutoService(Plugin.class)
public class MyLogLoaderPlugin implements Plugin {
    @Override
    public PluginMetaData metadata() {
        return new MyLogLoaderPluginMetaData();
    }

    @Override
    public Collection<org.graylog2.plugin.PluginModule> modules() {
        return Collections.singleton(new MyLogLoaderModule());
    }
}

