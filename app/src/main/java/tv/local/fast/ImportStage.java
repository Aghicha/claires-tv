package tv.local.fast;

import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;

/** A disposable database lets downloads and parsing run without locking the saved catalog. */
final class ImportStage implements AutoCloseable {
    private final File file;
    private SQLiteDatabase db;
    ImportStage(Catalog catalog) throws IOException {
        File directory=catalog.context().getCacheDir();
        clearOld(directory,System.currentTimeMillis()-24L*60*60*1000);
        file=File.createTempFile("catalog-import-", ".db",directory);
        db=SQLiteDatabase.openOrCreateDatabase(file,null);
    }
    static void clearOld(File directory,long before){
        File[] files=directory.listFiles((dir,name)->name.startsWith("catalog-import-")&&(name.endsWith(".db")||name.endsWith(".db-journal")||name.endsWith(".db-wal")||name.endsWith(".db-shm")));
        if(files!=null)for(File old:files)if(old.lastModified()<before)old.delete();
    }
    SQLiteDatabase database(){return db;}
    void attach(SQLiteDatabase target){db.close();db=null;target.execSQL("ATTACH DATABASE ? AS imported",new Object[]{file.getAbsolutePath()});}
    @Override public void close(){if(db!=null)db.close();file.delete();new File(file.getPath()+"-journal").delete();new File(file.getPath()+"-wal").delete();new File(file.getPath()+"-shm").delete();}
}
