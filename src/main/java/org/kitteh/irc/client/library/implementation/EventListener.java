/*
 * * Copyright (C) 2013-2015 Matt Baxter http://kitteh.org
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.kitteh.irc.client.library.implementation;

import net.engio.mbassy.listener.Filter;
import net.engio.mbassy.listener.Handler;
import net.engio.mbassy.listener.References;
import org.kitteh.irc.client.library.command.CapabilityRequestCommand;
import org.kitteh.irc.client.library.element.CapabilityState;
import org.kitteh.irc.client.library.element.ChannelModeStatusList;
import org.kitteh.irc.client.library.element.ChannelUserMode;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.event.abstractbase.CapabilityNegotiationResponseEventBase;
import org.kitteh.irc.client.library.event.capabilities.CapabilitiesAcknowledgedEvent;
import org.kitteh.irc.client.library.event.capabilities.CapabilitiesListEvent;
import org.kitteh.irc.client.library.event.capabilities.CapabilitiesRejectedEvent;
import org.kitteh.irc.client.library.event.capabilities.CapabilitiesSupportedListEvent;
import org.kitteh.irc.client.library.event.channel.ChannelCTCPEvent;
import org.kitteh.irc.client.library.event.channel.ChannelInviteEvent;
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent;
import org.kitteh.irc.client.library.event.channel.ChannelKickEvent;
import org.kitteh.irc.client.library.event.channel.ChannelKnockEvent;
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;
import org.kitteh.irc.client.library.event.channel.ChannelModeEvent;
import org.kitteh.irc.client.library.event.channel.ChannelNamesUpdatedEvent;
import org.kitteh.irc.client.library.event.channel.ChannelNoticeEvent;
import org.kitteh.irc.client.library.event.channel.ChannelPartEvent;
import org.kitteh.irc.client.library.event.channel.ChannelTargetedCTCPEvent;
import org.kitteh.irc.client.library.event.channel.ChannelTargetedMessageEvent;
import org.kitteh.irc.client.library.event.channel.ChannelTargetedNoticeEvent;
import org.kitteh.irc.client.library.event.channel.ChannelTopicEvent;
import org.kitteh.irc.client.library.event.channel.ChannelUsersUpdatedEvent;
import org.kitteh.irc.client.library.event.client.ClientConnectedEvent;
import org.kitteh.irc.client.library.event.client.ClientReceiveCommandEvent;
import org.kitteh.irc.client.library.event.client.ClientReceiveMOTDEvent;
import org.kitteh.irc.client.library.event.client.ClientReceiveNumericEvent;
import org.kitteh.irc.client.library.event.client.NickRejectedEvent;
import org.kitteh.irc.client.library.event.client.RequestedChannelJoinCompleteEvent;
import org.kitteh.irc.client.library.event.user.PrivateCTCPQueryEvent;
import org.kitteh.irc.client.library.event.user.PrivateCTCPReplyEvent;
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent;
import org.kitteh.irc.client.library.event.user.PrivateNoticeEvent;
import org.kitteh.irc.client.library.event.user.UserNickChangeEvent;
import org.kitteh.irc.client.library.event.user.UserQuitEvent;
import org.kitteh.irc.client.library.exception.KittehServerMessageException;
import org.kitteh.irc.client.library.util.CommandFilter;
import org.kitteh.irc.client.library.util.NumericFilter;
import org.kitteh.irc.client.library.util.StringUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@net.engio.mbassy.listener.Listener(references = References.Strong)
class EventListener {
    private final InternalClient client;

    EventListener(InternalClient client) {
        this.client = client;
    }

    @NumericFilter(1)
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void welcome(ClientReceiveNumericEvent event) {
        if (event.getArgs().length > 0) {
            this.client.setCurrentNick(event.getArgs()[0]);
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "Nickname unconfirmed.");
        }
    }

    @NumericFilter(4)
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void version(ClientReceiveNumericEvent event) {
        try {
            this.client.getAuthManager().authenticate();
        } catch (IllegalStateException | UnsupportedOperationException ignored) {
        }
        this.client.resetServerInfo();
        if (event.getArgs().length > 1) {
            this.client.getServerInfo().setAddress(event.getArgs()[1]);
            if (event.getArgs().length > 2) {
                this.client.getServerInfo().setVersion(event.getArgs()[2]);
            } else {
                throw new KittehServerMessageException(event.getOriginalMessage(), "Server version missing.");
            }
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "Server address and version missing.");
        }
        this.client.getEventManager().callEvent(new ClientConnectedEvent(this.client, event.getServer(), this.client.getServerInfo()));
        this.client.startSending();
    }

    @NumericFilter(5)
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void iSupport(ClientReceiveNumericEvent event) {
        for (int i = 1; i < event.getArgs().length; i++) {
            ISupport.handle(event.getArgs()[i], this.client);
        }
    }

    @NumericFilter(352) // WHO
    @NumericFilter(354) // WHOX
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void who(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < ((event.getNumeric() == 352) ? 8 : 9)) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "WHO response of incorrect length");
        }
        final ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[1]);
        if (channel != null) {
            final String ident = event.getArgs()[2];
            final String host = event.getArgs()[3];
            final String server = event.getArgs()[4];
            final String nick = event.getArgs()[5];
            final ActorProvider.IRCUser user = (ActorProvider.IRCUser) this.client.getActorProvider().getActor(nick + '!' + ident + '@' + host);
            user.setServer(server);
            final String status = event.getArgs()[6];
            String realName = null;
            switch (event.getNumeric()) {
                case 352:
                    realName = event.getArgs()[7];
                    break;
                case 354:
                    String account = event.getArgs()[7];
                    user.setAccount("0".equals(account) ? null : account);
                    realName = event.getArgs()[8];
                    break;
            }
            user.setRealName(realName);
            final Set<ChannelUserMode> modes = new HashSet<>();
            for (char prefix : status.substring(1).toCharArray()) {
                if (prefix == 'G') {
                    user.setAway(true);
                    continue;
                }
                for (ChannelUserMode mode : this.client.getServerInfo().getChannelUserModes()) {
                    if (mode.getPrefix() == prefix) {
                        modes.add(mode);
                        break;
                    }
                }
            }
            channel.trackUser(user, modes);
        } // No else, server might send other WHO information about non-channels.
    }

    @NumericFilter(315) // WHO completed
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void whoComplete(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "WHO response of incorrect length");
        }
        ActorProvider.IRCChannel whoChannel = this.client.getActorProvider().getChannel(event.getArgs()[1]);
        if (whoChannel != null) {
            whoChannel.setListReceived();
            this.client.getEventManager().callEvent(new ChannelUsersUpdatedEvent(this.client, whoChannel.snapshot()));
        } // No else, server might send other WHO information about non-channels.
    }

    @NumericFilter(324)
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void channelMode(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < 3) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "Channel mode info message of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[1]);
        if (channel != null) {
            ChannelModeStatusList statusList = ChannelModeStatusList.from(this.client, StringUtil.combineSplit(event.getArgs(), 2));
            channel.updateChannelModes(statusList);
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "Channel mode info message sent for invalid channel name");
        }
    }

    @NumericFilter(332) // Topic
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void topic(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "Topic message of incorrect length");
        }
        ActorProvider.IRCChannel topicChannel = this.client.getActorProvider().getChannel(event.getArgs()[1]);
        if (topicChannel != null) {
            topicChannel.setTopic(event.getArgs()[2]);
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "Topic message sent for invalid channel name");
        }
    }

    @NumericFilter(333) // Topic info
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void topicInfo(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < 4) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "Topic message of incorrect length");
        }
        ActorProvider.IRCChannel topicSetChannel = this.client.getActorProvider().getChannel(event.getArgs()[1]);
        if (topicSetChannel != null) {
            topicSetChannel.setTopic(Long.parseLong(event.getArgs()[3]) * 1000, this.client.getActorProvider().getActor(event.getArgs()[2]).snapshot());
            this.client.getEventManager().callEvent(new ChannelTopicEvent(this.client, topicSetChannel.snapshot(), false));
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "Topic message sent for invalid channel name");
        }
    }

    @NumericFilter(353) // NAMES
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void names(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < 4) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "NAMES response of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[2]);
        if (channel != null) {
            List<ChannelUserMode> channelUserModes = this.client.getServerInfo().getChannelUserModes();
            for (String combo : event.getArgs()[3].split(" ")) {
                Set<ChannelUserMode> modes = new HashSet<>();
                for (int i = 0; i < combo.length(); i++) {
                    char c = combo.charAt(i);
                    Optional<ChannelUserMode> mode = channelUserModes.stream().filter(m -> m.getPrefix() == c).findFirst();
                    if (mode.isPresent()) {
                        modes.add(mode.get());
                    } else {
                        channel.trackNick(combo.substring(i), modes);
                        break;
                    }
                }
            }
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "NAMES response sent for invalid channel name");
        }
    }

    @NumericFilter(366) // End of NAMES
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void namesComplete(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "NAMES response of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[1]);
        if (channel != null) {
            this.client.getEventManager().callEvent(new ChannelNamesUpdatedEvent(this.client, channel.snapshot()));
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "NAMES response sent for invalid channel name");
        }
    }

    private final List<String> motd = new LinkedList<>();

    @NumericFilter(375)
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void motdStart(ClientReceiveNumericEvent event) {
        this.motd.clear();
    }

    @NumericFilter(372)
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void motdContent(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "MOTD message of incorrect length");
        }
        this.motd.add(event.getArgs()[1]);
    }

    @NumericFilter(376)
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void motdEnd(ClientReceiveNumericEvent event) {
        this.client.getServerInfo().setMOTD(new ArrayList<>(this.motd));
        this.client.getEventManager().callEvent(new ClientReceiveMOTDEvent(this.client));
    }

    @NumericFilter(431) // No nick given
    @NumericFilter(432) // Erroneous nickname
    @NumericFilter(433) // Nick in use
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void nickInUse(ClientReceiveNumericEvent event) {
        NickRejectedEvent nickRejectedEvent = new NickRejectedEvent(this.client, this.client.getRequestedNick(), this.client.getRequestedNick() + '`');
        this.client.getEventManager().callEvent(nickRejectedEvent);
        this.client.sendNickChange(nickRejectedEvent.getNewNick());
    }

    @NumericFilter(710) // Knock
    @Handler(filters = @Filter(NumericFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void knock(ClientReceiveNumericEvent event) {
        if (event.getArgs().length < 3) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "KNOCK message of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[1]);
        if (channel != null) {
            ActorProvider.IRCUser user = (ActorProvider.IRCUser) this.client.getActorProvider().getActor(event.getArgs()[2]);
            this.client.getEventManager().callEvent(new ChannelKnockEvent(this.client, channel.snapshot(), user.snapshot()));
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "KNOCK message sent for invalid channel name");
        }
    }

    @CommandFilter("NOTICE")
    @CommandFilter("PRIVMSG")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void ctcp(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 2) {
            return; // Nothing to do here, handle that issue in the individual methods
        }
        if (CTCPUtil.isCTCP(event.getArgs()[1])) {
            final String ctcpMessage = CTCPUtil.fromCTCP(event.getArgs()[1]);
            final MessageTargetInfo messageTargetInfo = this.getTypeByTarget(event.getArgs()[0]);
            User user = (User) event.getActor();
            switch (event.getCommand()) {
                case "NOTICE":
                    if (messageTargetInfo instanceof MessageTargetInfo.Private) {
                        this.client.getEventManager().callEvent(new PrivateCTCPReplyEvent(this.client, user, ctcpMessage));
                    }
                    break;
                case "PRIVMSG":
                    if (messageTargetInfo instanceof MessageTargetInfo.Private) {
                        String reply = null; // Message to send as CTCP reply (NOTICE). Send nothing if null.
                        switch (ctcpMessage) {
                            case "VERSION":
                                reply = "VERSION I am Kitteh!";
                                break;
                            case "TIME":
                                reply = "TIME " + new Date().toString();
                                break;
                            case "FINGER":
                                reply = "FINGER om nom nom tasty finger";
                                break;
                        }
                        if (ctcpMessage.startsWith("PING ")) {
                            reply = ctcpMessage;
                        }
                        PrivateCTCPQueryEvent ctcpEvent = new PrivateCTCPQueryEvent(this.client, user, ctcpMessage, reply);
                        this.client.getEventManager().callEvent(ctcpEvent);
                        String eventReply = ctcpEvent.getReply();
                        if (eventReply != null) {
                            this.client.sendRawLine("NOTICE " + user.getNick() + " :" + CTCPUtil.toCTCP(eventReply));
                        }
                    } else if (messageTargetInfo instanceof MessageTargetInfo.Channel) {
                        MessageTargetInfo.Channel channelInfo = (MessageTargetInfo.Channel) messageTargetInfo;
                        this.client.getEventManager().callEvent(new ChannelCTCPEvent(this.client, user, channelInfo.getChannel().snapshot(), ctcpMessage));
                    } else if (messageTargetInfo instanceof MessageTargetInfo.TargetedChannel) {
                        MessageTargetInfo.TargetedChannel channelInfo = (MessageTargetInfo.TargetedChannel) messageTargetInfo;
                        this.client.getEventManager().callEvent(new ChannelTargetedCTCPEvent(this.client, user, channelInfo.getChannel().snapshot(), channelInfo.getPrefix(), ctcpMessage));
                    }
                    break;
            }
        }
    }

    @CommandFilter("CAP")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void cap(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 3) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "CAP message of incorrect length");
        }
        CapabilityNegotiationResponseEventBase responseEvent = null;
        List<CapabilityState> capabilityStateList = Arrays.stream(event.getArgs()[2].split(" ")).map(IRCCapabilityManager.IRCCapabilityState::new).collect(Collectors.toList());
        switch (event.getArgs()[1].toLowerCase()) {
            case "ack":
                this.client.getCapabilityManager().updateCapabilities(capabilityStateList);
                responseEvent = new CapabilitiesAcknowledgedEvent(this.client, this.client.getCapabilityManager().isNegotiating(), capabilityStateList);
                this.client.getEventManager().callEvent(responseEvent);
                break;
            case "list":
                this.client.getCapabilityManager().setCapabilities(capabilityStateList);
                this.client.getEventManager().callEvent(new CapabilitiesListEvent(this.client, capabilityStateList));
                break;
            case "ls":
                this.client.getCapabilityManager().setSupportedCapabilities(capabilityStateList);
                responseEvent = new CapabilitiesSupportedListEvent(this.client, this.client.getCapabilityManager().isNegotiating(), capabilityStateList);
                Set<String> capabilities = capabilityStateList.stream().map(CapabilityState::getCapabilityName).collect(Collectors.toCollection(HashSet::new));
                capabilities.retainAll(Arrays.asList("account-notify", "away-notify", "extended-join", "multi-prefix"));
                if (!capabilities.isEmpty()) {
                    CapabilityRequestCommand capabilityRequestCommand = new CapabilityRequestCommand(this.client);
                    capabilities.forEach(capabilityRequestCommand::requestEnable);
                    capabilityRequestCommand.execute();
                }
                this.client.getEventManager().callEvent(responseEvent);
                break;
            case "nak":
                this.client.getCapabilityManager().updateCapabilities(capabilityStateList);
                responseEvent = new CapabilitiesRejectedEvent(this.client, this.client.getCapabilityManager().isNegotiating(), capabilityStateList);
                this.client.getEventManager().callEvent(responseEvent);
                break;
        }
        if (responseEvent != null) {
            if (responseEvent.isNegotiating() && responseEvent.isEndingNegotiation()) {
                this.client.sendRawLineImmediately("CAP END");
                this.client.getCapabilityManager().endNegotiation();
            }
        }
    }

    @CommandFilter("ACCOUNT")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void account(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 1) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "ACCOUNT message of incorrect length");
        }
        String account = event.getArgs()[0];
        this.client.getActorProvider().trackUserAccount(((User) event.getActor()).getNick(), "*".equals(account) ? null : account);
    }

    @CommandFilter("AWAY")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void away(ClientReceiveCommandEvent event) {
        this.client.getActorProvider().trackUserAway(((User) event.getActor()).getNick(), event.getArgs().length > 0);
    }

    @CommandFilter("NOTICE")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void notice(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "NOTICE message of incorrect length");
        }
        if (!(event.getActor() instanceof User)) {
            return; // TODO handle this
        }
        User user = (User) event.getActor();
        MessageTargetInfo messageTargetInfo = this.getTypeByTarget(event.getArgs()[0]);
        if (messageTargetInfo instanceof MessageTargetInfo.Private) {
            this.client.getEventManager().callEvent(new PrivateNoticeEvent(this.client, user, event.getArgs()[1]));
        } else if (messageTargetInfo instanceof MessageTargetInfo.Channel) {
            MessageTargetInfo.Channel channelInfo = (MessageTargetInfo.Channel) messageTargetInfo;
            this.client.getEventManager().callEvent(new ChannelNoticeEvent(this.client, user, channelInfo.getChannel().snapshot(), event.getArgs()[1]));
        } else if (messageTargetInfo instanceof MessageTargetInfo.TargetedChannel) {
            MessageTargetInfo.TargetedChannel channelInfo = (MessageTargetInfo.TargetedChannel) messageTargetInfo;
            this.client.getEventManager().callEvent(new ChannelTargetedNoticeEvent(this.client, user, channelInfo.getChannel().snapshot(), channelInfo.getPrefix(), event.getArgs()[1]));
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "NOTICE message to improper target");
        }
    }

    @CommandFilter("PRIVMSG")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void privmsg(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "PRIVMSG message of incorrect length");
        }
        if (CTCPUtil.isCTCP(event.getArgs()[1])) {
            return;
        }
        if (!(event.getActor() instanceof User)) {
            return; // TODO handle this
        }
        User user = (User) event.getActor();
        MessageTargetInfo messageTargetInfo = this.getTypeByTarget(event.getArgs()[0]);
        if (messageTargetInfo instanceof MessageTargetInfo.Private) {
            this.client.getEventManager().callEvent(new PrivateMessageEvent(this.client, user, event.getArgs()[1]));
        } else if (messageTargetInfo instanceof MessageTargetInfo.Channel) {
            MessageTargetInfo.Channel channelInfo = (MessageTargetInfo.Channel) messageTargetInfo;
            this.client.getEventManager().callEvent(new ChannelMessageEvent(this.client, user, channelInfo.getChannel().snapshot(), event.getArgs()[1]));
        } else if (messageTargetInfo instanceof MessageTargetInfo.TargetedChannel) {
            MessageTargetInfo.TargetedChannel channelInfo = (MessageTargetInfo.TargetedChannel) messageTargetInfo;
            this.client.getEventManager().callEvent(new ChannelTargetedMessageEvent(this.client, user, channelInfo.getChannel().snapshot(), channelInfo.getPrefix(), event.getArgs()[1]));
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "PRIVMSG message to improper target");
        }
    }

    @CommandFilter("MODE")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void mode(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "MODE message of incorrect length");
        }
        MessageTargetInfo messageTargetInfo = this.getTypeByTarget(event.getArgs()[0]);
        if (messageTargetInfo instanceof MessageTargetInfo.Private) {
            // TODO listen to mode on self
        } else if (messageTargetInfo instanceof MessageTargetInfo.Channel) {
            ActorProvider.IRCChannel channel = ((MessageTargetInfo.Channel) messageTargetInfo).getChannel();
            ChannelModeStatusList statusList;
            try {
                statusList = ChannelModeStatusList.from(this.client, StringUtil.combineSplit(event.getArgs(), 1));
            } catch (IllegalArgumentException e) {
                throw new KittehServerMessageException(event.getOriginalMessage(), e.getMessage());
            }
            this.client.getEventManager().callEvent(new ChannelModeEvent(this.client, event.getActor(), channel.snapshot(), statusList));
            statusList.getStatuses().stream().filter(status -> (status.getMode() instanceof ChannelUserMode) && (status.getParameter() != null)).forEach(status -> {
                if (status.isSetting()) {
                    channel.trackUserModeAdd(status.getParameter(), (ChannelUserMode) status.getMode());
                } else {
                    channel.trackUserModeRemove(status.getParameter(), (ChannelUserMode) status.getMode());
                }
            });
            channel.updateChannelModes(statusList);
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "MODE message sent for invalid target");
        }
    }

    @CommandFilter("JOIN")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void join(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 1) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "JOIN message of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[0]);
        if (channel != null) {
            if (event.getActor() instanceof User) {
                ActorProvider.IRCUser user = (ActorProvider.IRCUser) this.client.getActorProvider().getActor(event.getActor().getName());
                channel.trackUser(user, null);
                ChannelJoinEvent joinEvent = null;
                if (user.getNick().equals(this.client.getNick())) {
                    this.client.getActorProvider().channelTrack(channel);
                    this.client.sendRawLine("MODE " + channel.getName());
                    this.client.sendRawLine("WHO " + channel.getName() + (this.client.getServerInfo().hasWhoXSupport() ? " %cuhsnfar" : ""));
                    if (this.client.getIntendedChannels().contains(channel.getName())) {
                        joinEvent = new RequestedChannelJoinCompleteEvent(this.client, channel.snapshot(), user.snapshot());
                    }
                }
                if (event.getArgs().length > 2) {
                    if (!"*".equals(event.getArgs()[1])) {
                        user.setAccount(event.getArgs()[1]);
                    }
                    user.setRealName(event.getArgs()[2]);
                }
                if (joinEvent == null) {
                    joinEvent = new ChannelJoinEvent(this.client, channel.snapshot(), user.snapshot());
                }
                this.client.getEventManager().callEvent(joinEvent);
            } else {
                throw new KittehServerMessageException(event.getOriginalMessage(), "JOIN message sent for non-user");
            }
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "JOIN message sent for invalid channel name");
        }
    }

    @CommandFilter("PART")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void part(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 1) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "PART message of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[0]);
        if (channel != null) {
            if (event.getActor() instanceof User) {
                User user = (User) event.getActor();
                this.client.getEventManager().callEvent(new ChannelPartEvent(this.client, channel.snapshot(), user, (event.getArgs().length > 1) ? event.getArgs()[1] : ""));
                channel.trackUserPart(user.getNick());
                if (user.getNick().equals(this.client.getNick())) {
                    this.client.getActorProvider().channelUntrack(channel);
                }
            } else {
                throw new KittehServerMessageException(event.getOriginalMessage(), "PART message sent for non-user");
            }
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "PART message sent for invalid channel name");
        }
    }

    @CommandFilter("QUIT")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void quit(ClientReceiveCommandEvent event) {
        if (event.getActor() instanceof User) {
            this.client.getEventManager().callEvent(new UserQuitEvent(this.client, (User) event.getActor(), (event.getArgs().length > 0) ? event.getArgs()[0] : ""));
            this.client.getActorProvider().trackUserQuit(((User) event.getActor()).getNick());
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "QUIT message sent for non-user");
        }
    }

    @CommandFilter("KICK")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void kick(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "KICK message of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[0]);
        if (channel != null) {
            ActorProvider.IRCUser kickedUser = this.client.getActorProvider().getUser(event.getArgs()[1]);
            if (kickedUser != null) {
                this.client.getEventManager().callEvent(new ChannelKickEvent(this.client, channel.snapshot(), (User) event.getActor(), kickedUser.snapshot(), (event.getArgs().length > 2) ? event.getArgs()[2] : ""));
                channel.trackUserPart(event.getArgs()[1]);
                if (event.getArgs()[1].equals(this.client.getNick())) {
                    this.client.getActorProvider().channelUntrack(channel);
                }
            } else {
                throw new KittehServerMessageException(event.getOriginalMessage(), "KICK message sent for non-user");
            }
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "KICK message sent for invalid channel name");
        }
    }

    @CommandFilter("NICK")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void nick(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 1) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "NICK message of incorrect length");
        }
        if (event.getActor() instanceof User) {
            ActorProvider.IRCUser user = this.client.getActorProvider().getUser(((User) event.getActor()).getNick());
            if (user == null) {
                throw new KittehServerMessageException(event.getOriginalMessage(), "NICK message sent for user not in tracked channels");
            }
            User oldUser = user.snapshot();
            if (user.getNick().equals(this.client.getNick())) {
                this.client.setCurrentNick(event.getArgs()[0]);
            }
            this.client.getActorProvider().trackUserNick(user.getNick(), event.getArgs()[0]);
            User newUser = user.snapshot();
            this.client.getEventManager().callEvent(new UserNickChangeEvent(this.client, oldUser, newUser));
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "NICK message sent for non-user");
        }
    }

    @CommandFilter("INVITE")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void invite(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "INVITE message of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[1]);
        if (channel != null) {
            if (this.client.getNick().equalsIgnoreCase(event.getArgs()[0]) && this.client.getIntendedChannels().contains(channel.getName())) {
                this.client.sendRawLine("JOIN " + channel.getName());
            }
            this.client.getEventManager().callEvent(new ChannelInviteEvent(this.client, channel.snapshot(), event.getActor(), event.getArgs()[0]));
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "INVITE message sent for invalid channel name");
        }
    }

    @CommandFilter("TOPIC")
    @Handler(filters = @Filter(CommandFilter.Filter.class), priority = Integer.MAX_VALUE - 1)
    public void topic(ClientReceiveCommandEvent event) {
        if (event.getArgs().length < 2) {
            throw new KittehServerMessageException(event.getOriginalMessage(), "TOPIC message of incorrect length");
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(event.getArgs()[0]);
        if (channel != null) {
            channel.setTopic(event.getArgs()[1]);
            channel.setTopic(System.currentTimeMillis(), event.getActor());
            this.client.getEventManager().callEvent(new ChannelTopicEvent(this.client, channel.snapshot(), true));
        } else {
            throw new KittehServerMessageException(event.getOriginalMessage(), "TOPIC message sent for invalid channel name");
        }
    }

    private static class MessageTargetInfo {
        private static class Channel extends MessageTargetInfo {
            private final ActorProvider.IRCChannel channel;

            private Channel(ActorProvider.IRCChannel channel) {
                this.channel = channel;
            }

            @Nonnull
            ActorProvider.IRCChannel getChannel() {
                return this.channel;
            }
        }

        private static class TargetedChannel extends MessageTargetInfo {
            private final ActorProvider.IRCChannel channel;
            private final ChannelUserMode prefix;

            private TargetedChannel(ActorProvider.IRCChannel channel, ChannelUserMode prefix) {
                this.channel = channel;
                this.prefix = prefix;
            }

            @Nonnull
            ActorProvider.IRCChannel getChannel() {
                return this.channel;
            }

            @Nonnull
            ChannelUserMode getPrefix() {
                return this.prefix;
            }
        }

        private static class Private extends MessageTargetInfo {
            static final Private INSTANCE = new Private();
        }

        static final MessageTargetInfo UNKNOWN = new MessageTargetInfo();
    }

    @Nonnull
    MessageTargetInfo getTypeByTarget(@Nonnull String target) {
        if (this.client.getNick().equalsIgnoreCase(target)) {
            return MessageTargetInfo.Private.INSTANCE;
        }
        ActorProvider.IRCChannel channel = this.client.getActorProvider().getChannel(target);
        ChannelUserMode prefix = this.client.getServerInfo().getTargetedChannelInfo(target);
        if (channel != null) {
            if (prefix != null) {
                return new MessageTargetInfo.TargetedChannel(channel, prefix);
            } else {
                return new MessageTargetInfo.Channel(channel);
            }
        }
        return MessageTargetInfo.UNKNOWN;
    }
}