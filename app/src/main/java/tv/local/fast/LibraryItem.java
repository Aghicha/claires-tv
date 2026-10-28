package tv.local.fast;

final class LibraryItem {
    final String kind,id,name,category,extension,summary,image;
    final boolean favorite;
    LibraryItem(String kind,String id,String name,String category,String extension,String summary,String image,boolean favorite){
        this.kind=kind;this.id=id;this.name=name;this.category=category;this.extension=extension;this.summary=summary;this.image=image;this.favorite=favorite;
    }
    String progressKey(){return kind+":"+id;}
    @Override public String toString(){return (favorite?"★  ":"")+name;}
}
