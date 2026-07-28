package com.chat;

import com.chat.proto.ChatProto;
import io.netty.channel.Channel;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe chat room state.
 *
 * Stores:
 *  - connected users  (channel <-> name bidirectional map)
 *  - user avatars     (name -> bytes)
 *  - message history  (up to MAX_HISTORY entries, up to MAX_IMAGES image entries)
 *
 * History is persisted to HISTORY_FILE using protobuf's writeDelimitedTo /
 * parseDelimitedFrom so it survives server restarts.
 */
public class ChatRoom {

    private static final int    MAX_USERS    = 100;
    private static final int    MAX_HISTORY  = 50;
    private static final int    MAX_IMAGES   = 50;
    private final File historyFile;

    // channel -> username
    private final Map<Channel, String> channelToName   = new ConcurrentHashMap<>();
    // username -> channel
    private final Map<String, Channel> nameToChannel   = new ConcurrentHashMap<>();
    // username -> avatar bytes
    private final Map<String, byte[]>  userAvatars     = new ConcurrentHashMap<>();
    // username -> avatar MIME
    private final Map<String, String>  userAvatarMimes = new ConcurrentHashMap<>();

    private final List<ChatProto.HistoryMessage> history = new ArrayList<>();
    private int    imageCount   = 0;
    private byte[] lastImage    = null;
    private String lastImageMime = "";

    public ChatRoom() {
        this(new File("chat_history.bin"));
    }

    public ChatRoom(File historyFile) {
        this.historyFile = Objects.requireNonNull(historyFile);
        loadHistory();
    }

    // ------------------------------------------------------------------ join/leave

    public synchronized JoinResult join(Channel channel, String name,
                                        byte[] avatar, String avatarMime) {
        if (channelToName.containsKey(channel)) {
            return JoinResult.ALREADY_JOINED;
        }
        if (nameToChannel.containsKey(name)) {
            return JoinResult.NAME_TAKEN;
        }
        if (channelToName.size() >= MAX_USERS) {
            return JoinResult.ROOM_FULL;
        }

        channelToName.put(channel, name);
        nameToChannel.put(name, channel);

        if (avatar != null && avatar.length > 0) {
            userAvatars.put(name, avatar);
            userAvatarMimes.put(name, avatarMime != null ? avatarMime : "image/jpeg");
        }

        return JoinResult.OK;
    }

    public synchronized void leave(Channel channel) {
        String name = channelToName.remove(channel);
        if (name != null) {
            nameToChannel.remove(name, channel);
            // History messages already contain their own avatar bytes.
            userAvatars.remove(name);
            userAvatarMimes.remove(name);
        }
    }

    // ------------------------------------------------------------------ lookups

    public String getUserName(Channel channel) {
        return channelToName.get(channel);
    }

    public Channel getChannelByName(String name) {
        return nameToChannel.get(name);
    }

    public boolean isUserOnline(String name) {
        return nameToChannel.containsKey(name);
    }

    public byte[] getUserAvatar(String name) {
        return userAvatars.get(name);
    }

    public String getUserAvatarMime(String name) {
        return userAvatarMimes.getOrDefault(name, "");
    }

    public int getActiveUserCount() {
        return channelToName.size();
    }

    public Collection<Channel> getAllChannels() {
        return channelToName.keySet();
    }

    // ------------------------------------------------------------------ history

    public synchronized List<ChatProto.HistoryMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public synchronized void addToHistory(ChatProto.HistoryMessage message) {
        if (message.getHasImage() && imageCount >= MAX_IMAGES) {
            // Remove the oldest message that carries an image
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).getHasImage()) {
                    history.remove(i);
                    imageCount--;
                    break;
                }
            }
        }

        history.add(message);
        if (history.size() > MAX_HISTORY) {
            ChatProto.HistoryMessage removed = history.remove(0);
            if (removed.getHasImage()) imageCount--;
        }

        if (message.getHasImage()) {
            imageCount++;
            lastImage    = message.getImage().toByteArray();
            lastImageMime = message.getImageMime();
        }

        saveHistory();
    }

    public byte[] getLastImage() {
        return lastImage;
    }

    public String getLastImageMime() {
        return lastImageMime;
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Loads history from HISTORY_FILE on startup using protobuf's delimited format.
     * Each HistoryMessage is prefixed with a varint length, so we can read until EOF.
     */
    private void loadHistory() {
        File file = historyFile;
        if (!file.exists()) return;

        try (FileInputStream fis = new FileInputStream(file)) {
            ChatProto.HistoryMessage msg;
            while ((msg = ChatProto.HistoryMessage.parseDelimitedFrom(fis)) != null) {
                history.add(msg);
                if (msg.getHasImage()) {
                    imageCount++;
                    lastImage    = msg.getImage().toByteArray();
                    lastImageMime = msg.getImageMime();
                }
            }
            System.out.printf("[HISTORY] Loaded %d messages from %s%n",
                    history.size(), historyFile);
        } catch (IOException e) {
            System.err.println("[HISTORY] Failed to load history: " + e.getMessage());
        }
    }

    /**
     * Saves the current history list to HISTORY_FILE after every new message.
     * Uses protobuf's writeDelimitedTo — each message is prefixed with its length.
     */
    private void saveHistory() {
        try (FileOutputStream fos = new FileOutputStream(historyFile)) {
            for (ChatProto.HistoryMessage msg : history) {
                msg.writeDelimitedTo(fos);
            }
        } catch (IOException e) {
            System.err.println("[HISTORY] Failed to save history: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ enum

    public enum JoinResult { OK, ALREADY_JOINED, NAME_TAKEN, ROOM_FULL }
}
