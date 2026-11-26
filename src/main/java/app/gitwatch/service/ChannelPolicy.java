package app.gitwatch.service;

import app.gitwatch.model.ChannelTypes;

public final class ChannelPolicy {

    private ChannelPolicy() {
    }

    public static boolean isTelegram(String type) {
        return type != null && ChannelTypes.TELEGRAM.equalsIgnoreCase(type.trim());
    }

    public static boolean isDiscord(String type) {
        return type != null && ChannelTypes.DISCORD.equalsIgnoreCase(type.trim());
    }

    public static void rejectCreate(String type) {
        if (isTelegram(type)) {
            throw new UnsupportedChannelException("telegram channels are not supported");
        }
    }
}
