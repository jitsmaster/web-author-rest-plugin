package com.oxygenxml.rest.plugin;

import static com.icl.saxon.exslt.Date.date;
import java.nio.file.attribute.FileTime;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;

/**
 *
 * @author awang
 */
public class DtdModel {

    public String Id;
    public String Name;
    public String LastUpdated;

    private FileTime _lastUpdatedTime = null;

    public FileTime getLastUpdatedTime() {
        if (_lastUpdatedTime == null) {
            try {
                int i = LastUpdated.lastIndexOf(".");
                String ludS = ( i > -1) ? LastUpdated.substring(0, i) : LastUpdated;

                long milis = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss").parse(ludS)
                        .getTime();
                _lastUpdatedTime = FileTime.fromMillis(milis);
            } catch (ParseException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return _lastUpdatedTime;
    }

    public void setLastUpdatedTime(FileTime time) {
        _lastUpdatedTime = time;
    }
}
