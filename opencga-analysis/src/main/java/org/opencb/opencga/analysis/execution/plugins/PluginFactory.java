/*
 * Copyright 2015-2016 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.analysis.execution.plugins;

import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * This class scans all the classpath using the library {@link Reflections} and find all the
 * implementations of the plugin interface {@link OpenCGAAnalysis}.
 *
 * Implements singleton pattern. Use {@link #get()} to obtain instance
 *
 * Created on 27/11/15
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class PluginFactory {
    private Logger logger = LoggerFactory.getLogger(PluginFactory.class);

    private static PluginFactory pluginFactory;
    private final Reflections reflections;
    private final Map<String, Class<? extends OpenCGAAnalysis>> pluginsIdMap = new HashMap<>();

    private PluginFactory() {
//        Reflections.log = null; // Uncomment to skip logs
        reflections = new Reflections(new ConfigurationBuilder()
                .setScanners(new SubTypesScanner()).addUrls(ClasspathHelper.forJavaClassPath())
                .filterInputsBy(input -> input.endsWith(".class"))
        );
        init();
    }

    /**
     * Initialize the pluginsIdMap. Find all the subtypes of {@link OpenCGAAnalysis}
     */
    private void init() {
        Set<Class<? extends OpenCGAAnalysis>> plugins = reflections.getSubTypesOf(OpenCGAAnalysis.class);
        List<String> duplicatedPlugins = new LinkedList<>();
        for (Class<? extends OpenCGAAnalysis> pluginClazz : plugins) {
            try {
                OpenCGAAnalysis plugin = pluginClazz.newInstance();
                String pluginId = plugin.getIdentifier();
                if (pluginsIdMap.containsKey(pluginId)) {
                    logger.error("Duplicated ID for class {} and {}", pluginClazz, pluginsIdMap.get(pluginId));
                    duplicatedPlugins.add(pluginId);
                    continue;
                }
                pluginsIdMap.put(pluginId, pluginClazz);
            } catch (InstantiationException | IllegalAccessException | RuntimeException e) {
                logger.error("Unable to load class {} ", pluginClazz);
            }
        }
        duplicatedPlugins.forEach(pluginsIdMap::remove);
    }

    /**
     * Singleton accessor method
     *
     * @return Get the singleton instance of {@link PluginFactory}
     */
    public static PluginFactory get() {
        if (pluginFactory == null) {
            pluginFactory = new PluginFactory();
        }
        return pluginFactory;
    }

    /**
     * Get all the found plugin classes
     *
     * @return  Map between plugin id and plugin class
     */
    public Map<String, Class<? extends OpenCGAAnalysis>> getAllPlugins() {
        return Collections.unmodifiableMap(pluginsIdMap);
    }

    /**
     * Get the class of a plugin given its id
     *
     * @param id    Plugin id
     * @return      Plugin class
     */
    public Class<? extends OpenCGAAnalysis> getPluginClass(String id) {
        return pluginsIdMap.get(id);
    }

    /**
     * Get a new instance of a plugin given its id.
     *
     * @param id    Plugin id
     * @return      New instance of the plugin
     */
    public OpenCGAAnalysis getPlugin(String id) {
        try {
            Class<? extends OpenCGAAnalysis> pluginClass = getPluginClass(id);
            if (pluginClass == null) {
                return null;
            } else {
                return pluginClass.newInstance();
            }
        } catch (InstantiationException | IllegalAccessException e) {
            //This should never happen. All the plugins in the Map can be instantiated
            throw new IllegalStateException("Error creating new instance");
        }
    }

}
