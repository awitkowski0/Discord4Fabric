package me.reimnop.d4f;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.AllowedMentions;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.pb4.placeholders.api.*;
import me.reimnop.d4f.exceptions.ChannelException;
import me.reimnop.d4f.exceptions.GuildException;
import me.reimnop.d4f.listeners.DiscordMessageListener;
import me.reimnop.d4f.utils.Utils;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.samo_lego.fabrictailor.casts.TailoredPlayer;

import javax.annotation.Nullable;
import javax.security.auth.login.LoginException;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class Discord {
    public final JDA jda;

    @Nullable
    private final WebhookClient webhookClient;

    private final Config config;
    private final Map<String, Emote> emotes = new HashMap<>();

    public final SelfUser selfUser;

    public Discord(Config config) throws LoginException, InterruptedException {
        this.config = config;

        // init jda
        JDABuilder builder = JDABuilder
                .createDefault(config.token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(MemberCachePolicy.ALL);
        jda = builder.build();
        jda.addEventListener(new DiscordMessageListener());
        jda.awaitReady();

        selfUser = jda.getSelfUser();

        // init webhook
        webhookClient = "".equals(config.webhookUrl) ? null : WebhookClient.withUrl(config.webhookUrl);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void initCache() throws GuildException {
        getGuild().loadMembers();

        emotes.clear();
        for (Emote emote : getGuild().getEmotes()) {
            emotes.put(emote.getName(), emote);
        }
    }

    public void close() {
        jda.shutdown();

        if (webhookClient != null) {
            webhookClient.close();
        }
    }

    public Guild getGuild() throws GuildException {
        Guild guild = jda.getGuildById(config.guildId);
        if (guild == null) {
            throw new GuildException(config.guildId);
        }
        return guild;
    }

    private TextChannel getTextChannel() throws GuildException, ChannelException {
        TextChannel channel = getGuild().getChannelById(TextChannel.class, config.channelId);
        if (channel == null) {
            throw new ChannelException(config.channelId);
        }
        return channel;
    }

    private Guild getConsoleGuild() throws GuildException {
        Guild guild = jda.getGuildById(config.consoleGuildId);
        if (guild == null) {
            throw new GuildException(config.consoleGuildId);
        }
        return guild;
    }

    public TextChannel getConsoleChannel() throws GuildException, ChannelException {
        TextChannel channel = getConsoleGuild().getChannelById(TextChannel.class, config.consoleChannelId);
        if (channel == null) {
            throw new ChannelException(config.consoleChannelId);
        }
        return channel;
    }

    @Nullable
    public User getUser(Long id) {
        return jda.retrieveUserById(id).complete();
    }

    @Nullable
    public Member getMember(User user) {
        try {
            return getGuild().getMember(user);
        } catch (GuildException e) {
            Utils.logException(e);
        }
        return null;
    }

    @Nullable
    public Member getMember(Long id) {
        try {
            return getGuild().getMemberById(id);
        } catch (GuildException e) {
            Utils.logException(e);
        }
        return null;
    }

    @Nullable
    public User findUser(String tag) throws GuildException {
        Member member = getGuild().getMemberByTag(tag);
        return member != null ? member.getUser() : null;
    }

    @Nullable
    public User findUserByName(String name) throws GuildException {
        List<Member> members = getGuild().findMembers(x -> x.getEffectiveName().equals(name)).get();
        return members.size() == 0 ? null : members.get(0).getUser();
    }

    @Nullable
    public Emote findEmote(String name) {
        return emotes.getOrDefault(name, null);
    }

    public void sendPlayerMessage(ServerPlayerEntity sender, Text name, Text message) {
        if (webhookClient != null) {
            WebhookMessageBuilder wmb = new WebhookMessageBuilder()
                    .setAvatarUrl(Utils.getAvatarUrl(sender))
                    .setUsername(name.getString())
                    .setContent(message.getString())
                    .setAllowedMentions(new AllowedMentions()
                            .withParseEveryone(false)
                            .withParseRoles(false)
                            .withParseUsers(true));

            webhookClient.send(wmb.build());
        } else {
            Map<Identifier, PlaceholderHandler> placeholders = Map.of(
                    Discord4Fabric.id("name"), (ctx, arg) -> PlaceholderResult.value(name),
                    Discord4Fabric.id("message"), (ctx, arg) -> PlaceholderResult.value(message)
            );
            Text msg = Placeholders.parseText(
                    TextParserUtils.formatText(config.webhookToPlainMessage),
                    PlaceholderContext.of(sender),
                    Placeholders.PLACEHOLDER_PATTERN,
                    placeholder -> Utils.getPlaceholderHandler(placeholder, placeholders)
            );
            sendPlainMessage(msg);
        }
    }

    public void sendEmbedMessageUsingPlayerAvatar(ServerPlayerEntity sender, Color color, String message, String description) {
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .setAuthor(message, null, Utils.getAvatarUrl(sender))
                .setDescription(description)
                .setColor(color);

        sendEmbedMessage(embedBuilder);
    }

    public void sendEmbedMessage(EmbedBuilder embedBuilder) {
        try {
            getTextChannel()
                    .sendMessage(new MessageBuilder()
                            .setEmbeds(embedBuilder.build())
                            .build())
                    .queue();
        } catch (Exception e) {
            Utils.logException(e);
        }
    }

    public void sendPlainMessage(String message) {
        try {
            getTextChannel()
                    .sendMessage(message)
                    .queue();
        } catch (Exception e) {
            Utils.logException(e);
        }
    }

    public void sendPlainMessage(Text message) {
        sendPlainMessage(message.getString());
    }

    public void setChannelTopic(Text topic) {
        try {
            getTextChannel()
                    .getManager()
                    .setTopic(topic.getString())
                    .queue();
        } catch (Exception e) {
            Utils.logException(e);
        }
    }

    public void setStatus(Text status) {
        jda.getPresence().setActivity(
                Activity.playing(status.getString())
        );
    }
}
