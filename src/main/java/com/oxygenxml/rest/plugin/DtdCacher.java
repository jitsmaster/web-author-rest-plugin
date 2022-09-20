package com.oxygenxml.rest.plugin;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.io.IOUtils;

/**
 *
 * @author Arnold Wang
 */
public class DtdCacher {

    ConcurrentHashMap<String, DtdCacheModel> dtdContents = new ConcurrentHashMap<String, DtdCacheModel>();

    RestURLConnection rest;

    final static String STORAGE_LOCATION = "Oxygen/igx_dtd_cache";

    String storageLocation;

    static volatile DtdCacher instance;

    public static DtdCacher get(RestURLConnection rest) {
        DtdCacher localRef = instance;
        if (localRef == null) {
            synchronized (DtdCacher.class) {
                localRef = instance;
                if (localRef == null) {
                    instance = localRef = new DtdCacher(rest);
                }
            }
        }
        return localRef;
    }

    private DtdCacher(RestURLConnection rest) {
        this.rest = rest;

        //load local files first
        try {
            File parent = new File(System.getProperty("user.home"));
            storageLocation = parent.getAbsolutePath() + "/" + STORAGE_LOCATION;
            //get the files under this location and push them to cache

            File dtdParent = new File(storageLocation);

            if (!dtdParent.exists()) {
                dtdParent.mkdirs();
            } else {
                for (File f : dtdParent.listFiles()) {
                    if (f.isFile()) {
                        DtdCacheModel dtdCacheModel = new DtdCacheModel(f);
                        dtdContents.put(dtdCacheModel.Name, dtdCacheModel);
                    }
                }
            }

            HashSet<String> idsToUpdate = new HashSet<String>();

            EnvironmentModel env = rest.getEnvironment();

            if (env != null) {

                final DtdModel[] dtds = env.Dtds;

                //check for update
                for (DtdModel d : dtds) {
                    if (!dtdContents.containsKey(d.Name)) {
                        idsToUpdate.add(d.Id);
                    } else {
                        DtdCacheModel cacheD = dtdContents.get(d.Name);
                        if (cacheD.getLastUpdatedTime().compareTo(d.getLastUpdatedTime()) < 0) {
                            idsToUpdate.add(d.Id);
                        }
                    }
                }
            }
            final int idsCount = idsToUpdate.size();

            if (idsCount > 0) {
                System.out.println("Found " + idsCount + " dtd to update");

                String arr[] = new String[idsToUpdate.size()];

                // toArray() method converts the set to array
                idsToUpdate.toArray(arr);

                //get dtd updates
                DtdModelWithContent[] updatedDtdContents = rest.getDtdContents(arr);

                if (updatedDtdContents != null) {
                    for (DtdModelWithContent d : updatedDtdContents) {
                        //save to disk first
                        FileWriter fw = new FileWriter(new File(storageLocation + "/" + d.Name));
                        fw.write(d.Content);
                        fw.flush();
                        fw.close();

                        //add to cache
                        dtdContents.put(d.Name,
                                new DtdCacheModel(d));

                        System.out.println("Saved dtd " + d.Name + " to cache");
                    }
                }
            }

        } catch (Exception e) {
            System.out.println(e);
        }
    }

    public InputStream getCache(String name) {
        if (dtdContents.containsKey(name)) {
            DtdCacheModel cache = dtdContents.get(name);
            if (cache != null) {
                return new ByteArrayInputStream(cache.Content);
            }
        }

        return null;
    }

    public void putCache(String name, InputStream strm) throws IOException {
        final DtdCacheModel dtdCacheModel = new DtdCacheModel(name, strm);
        dtdContents.put(name, dtdCacheModel);

        String content = new String(dtdCacheModel.Content, StandardCharsets.UTF_8);
        FileWriter fw = new FileWriter(new File(storageLocation + "/" + name));
        fw.write(content);
        fw.flush();
        fw.close();
    }
}
