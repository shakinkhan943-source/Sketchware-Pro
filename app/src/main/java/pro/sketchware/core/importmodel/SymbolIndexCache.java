package importmodel;

import java.io.*;
import java.util.*;

public final class SymbolIndexCache {
    private final File file;
    public SymbolIndexCache(File file){this.file=file;}
    public boolean isValid(String fingerprint){
        if(!file.isFile())return false;
        try{DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            try{return fingerprint.equals(in.readUTF());}finally{in.close();}
        }catch(Exception e){return false;}
    }
    public void writeFingerprint(String fingerprint)throws IOException{
        File p=file.getParentFile();if(p!=null)p.mkdirs();
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
        try{out.writeUTF(fingerprint==null?"":fingerprint);out.flush();}finally{out.close();}
    }
}
