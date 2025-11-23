package org.example.myplugin;

import org.graylog2.plugin.PluginModule;
import org.example.myplugin.services.SnapshotLogLoaderService;

public class MyLogLoaderModule extends PluginModule {
    @Override
    protected void configure() {
    	bind(SnapshotLogLoaderService.class).asEagerSingleton();
    	addPeriodical(SnapshotLogLoaderService.class);
}

}


