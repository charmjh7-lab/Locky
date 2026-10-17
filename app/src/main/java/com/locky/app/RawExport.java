package com.locky.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** Keeps export bytes out of Activity saved state and the Binder transaction. */
final class RawExport {
    static final String STATE_TOKEN="pending-raw-export-token";
    private static final String TOKEN="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final Set<String> active=new HashSet<>();
    /** Staged/picker-pending and writing exports are protected until success/cancel discards them. */
    static synchronized void cleanOld(Context context){
        File directory=new File(context.getFilesDir(),"raw-exports");File[] files=directory.listFiles();if(files==null)return;
        long cutoff=System.currentTimeMillis()-7L*86400000;
        for(File candidate:files){String name=candidate.getName();
            if(!name.matches(TOKEN+"\\.json")||!candidate.isFile()||java.nio.file.Files.isSymbolicLink(candidate.toPath()))continue;
            String token=name.substring(0,name.length()-5);long created=candidate.lastModified();
            if(!active.contains(token)&&created>0&&created<cutoff)candidate.delete();
        }
    }
    private static File file(Context context,String token) throws IOException {
        if(token==null||!token.matches(TOKEN))throw new IOException("Missing export token");
        return new File(new File(context.getFilesDir(),"raw-exports"),token+".json");
    }
    static synchronized String stage(Context context,String json) throws IOException {
        if(json==null||json.isBlank())throw new IOException("Empty export");
        String token=UUID.randomUUID().toString();File target=file(context,token),directory=target.getParentFile();
        if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("Cannot prepare export");
        active.add(token);
        try(Writer writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target),StandardCharsets.UTF_8))){writer.write(json);}
        catch(IOException failure){discard(context,token);throw failure;}
        return token;
    }
    static void write(Context context,String token,Uri destination) throws IOException {
        File source;
        synchronized(RawExport.class){source=file(context,token);active.add(token);}
        if(!source.isFile()||source.length()==0)throw new IOException("Export no longer available");
        // Close successfully before reporting completion; a provider can fail on close too.
        try(InputStream input=new BufferedInputStream(new FileInputStream(source));
            OutputStream output=context.getContentResolver().openOutputStream(destination,"wt")){
            if(output==null)throw new IOException("Cannot open destination");
            byte[] buffer=new byte[16384];int count;
            while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            output.flush();
        }
    }
    static synchronized void discard(Context context,String token){try{file(context,token).delete();}catch(IOException ignored){}finally{active.remove(token);}}
    static void removeFailedDocument(Context context,Uri destination){
        // ACTION_CREATE_DOCUMENT returned a new file belonging to this export attempt.
        try{DocumentsContract.deleteDocument(context.getContentResolver(),destination);}catch(Exception ignored){}
    }
}
