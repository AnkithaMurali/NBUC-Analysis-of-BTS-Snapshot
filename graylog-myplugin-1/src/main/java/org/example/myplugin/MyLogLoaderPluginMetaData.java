package org.example.myplugin;

import org.graylog2.plugin.PluginMetaData;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.Version;

import java.net.URI;
import java.util.Collections;
import java.util.Set;

public class MyLogLoaderPluginMetaData implements PluginMetaData {
    @Override
    public String getUniqueId() {
        return "org.example.myplugin.MyLogLoaderPlugin";
    }

    @Override
    public String getName() {
        return "Snapshot Log Loader";
    }

    @Override
    public String getAuthor() {
        return "Ankitha";
    }

    @Override
    public URI getURL() {
        return URI.create("https://example.org/myplugin");
    }

    @Override
    public Version getVersion() {
        return Version.from(1, 0, 0);
    }

    public Set<ServerStatus.Capability> getRequiredCapabilities() {
        return Collections.emptySet();
    }

    @Override
    public String getDescription() {
        return "Loads snapshot logs into Graylog and highlights ERR/WRN entries.";
    }

    @Override
    public Version getRequiredVersion() {
        return Version.from(6, 2, 3);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MyLogLoaderPluginMetaData;
    }

    @Override
    public int hashCode() {
        return getUniqueId().hashCode();
    }
}

