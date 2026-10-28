package tv.local.fast;

final class Channel {
    final String id, name, category, url;
    final boolean favorite;
    Channel(String id, String name, String category, String url, boolean favorite) {
        this.id=id; this.name=name; this.category=category; this.url=url; this.favorite=favorite;
    }
}
