/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.hive13.jircbot;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.hive13.jircbot.commands.jIBCLinkify;
import org.hive13.jircbot.commands.jIBCQuitCmd;
import org.hive13.jircbot.commands.jIBCTRssReader;
import org.hive13.jircbot.commands.jIBCTell;
import org.hive13.jircbot.commands.jIBCTimeCmd;
import org.hive13.jircbot.commands.jIBCommand;
import org.hive13.jircbot.commands.jIBCommandThread;
import org.hive13.jircbot.support.jIRCProperties;
import org.hive13.jircbot.support.jIRCTools;
import org.hive13.jircbot.support.jIRCTools.eMsgTypes;
import org.hive13.jircbot.support.jIRCUser.eAuthLevels;
import org.hive13.jircbot.support.jIRCUser;
import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.NickAlreadyInUseException;
import org.jibble.pircbot.PircBot;
import org.jibble.pircbot.User;

/**
 * 
 * @author vincenpt
 */
public class jIRCBot extends PircBot {
	// Store the commands
	/*
	 * Important Stuff for threadedCommands - We can have multiple cT w/ the
	 * same name. - What is unique about a tC then? - Name + channel, we can
	 * only have 1 tC per channel. - Fixed: Each command - Going to need to
	 * change the name for each instance per channel. - Each tC has the
	 * following: - tC implementation
	 */
	private final HashMap<String, jIBCommand> commands;
	private final ArrayList<jIBCommand> lineParseCommands;

	// The character which tells the bot we're talking to it and not
	// anyone/anything else.
	private final String prefix = "!";
	// Server to join
	private String serverAddress = "";
	// Username to use
	private String botName = "";

	// List of channels to join
	private final List<String> channelList;

	// Flag used to tell if the bot was actually told to quit vs. it timing out.
	public AtomicBoolean abShouldQuit = new AtomicBoolean(false);

	/*
	 * Ok, the userList is a bit hackish at the moment and there is the
	 * potential that it will leak some memory. Basically I try to
	 * programatically keep track of what users are in the channels the bot
	 * lives in and what their current usernames are.
	 * 
	 * The basic problem is this: I only remove users from the list when they
	 * leave a channel or quit the server. If they leave through other means
	 * (kick, ban, other?) they will get stuck in the userList until the bot
	 * leaves & rejoins the channel.
	 */

	/*
	 * !!!!!!!!! WARNING !!!!!!!!!!!!!! The userList is accessed from multiple
	 * threads Simultaneously. ONLY access it through approved safe methods. If
	 * you need to access a function that is not exposed through the one of
	 * these functions, you may write a new one using the userListMutex to
	 * ensure that only one thread is actively accessing the userList.
	 */
	private final HashMap<String, jIRCUser> userList;
	private Semaphore userListMutex;

	/**
	 * @param args
	 *            the command line arguments
	 */
	public static void main(String[] args) {
		Properties config = new Properties();
		try {
			config.load(new FileInputStream("jIRCBot.properties"));
		} catch (IOException ex) {
			Logger.getLogger(jIRCBot.class.getName()).log(Level.SEVERE, null,
					ex);
		}

		new jIRCBot();
	}

	private jIRCBot() {

		// Initialize lists
		commands = new HashMap<String, jIBCommand>();

		lineParseCommands = new ArrayList<jIBCommand>();

		channelList = new ArrayList<String>();

		userList = new HashMap<String, jIRCUser>();
		userListMutex = new Semaphore(1);

		// Grab configuration information.
		botName = jIRCProperties.getInstance().getBotName();
		serverAddress = jIRCProperties.getInstance().getServer();

		// Parse the list of channels to join.
		String strChannels[] = jIRCProperties.getInstance().getChannels();
		for (int i = 0; i < strChannels.length; i++) {
			String channel = strChannels[i].trim();
			if (channel.length() > 0) {
				channelList.add(channel);
			}
		}

		if (jIRCProperties.getInstance().getJDBCUrl().isEmpty()
				|| jIRCProperties.getInstance().getJDBCUser().isEmpty()
				|| jIRCProperties.getInstance().getJDBCPass().isEmpty()) {
			jIRCTools.jdbcEnabled = false;
		} else {
			jIRCTools.jdbcEnabled = true;
		}

		// Make it so that the bot outputs lots of information when run.
		setVerbose(true);

		if (jIRCTools.jdbcEnabled == false)
			this.log("MySQL Chat logging is disabled.", eLogLevel.warning);
		else
			this.log("MySQL Chat logging is enabled.", eLogLevel.info);

		// Add commands that parse every single line
		// WARNING!! Be sparing with these commands
		// their code should be run asynchronously
		addLineParseCommand(new jIBCTell());
		addLineParseCommand(new jIBCLinkify());

		// Add passive commands
		addCommand(new jIBCTimeCmd());
		addCommand(new jIBCQuitCmd());
		// addCommand(new jIBCPluginList(commands));
		// addCommand(new jIBCLogParser());
		try {
			// Add all command threads.
			
			addCommandThread(new jIBCTRssReader(
					this,
					"WikiFeed",
					channelList.get(0),
					"[commandName]: [Title|c50] ~[Author|c20] ([Link])",
					"http://wiki.hive13.org/index.php?title=Special:RecentChanges&feed=rss&hideminor=1"));

			addCommandThread(new jIBCTRssReader(this, "h13Blog",
					channelList.get(0), "[commandName]: [Title|c50] ([Link])",
					"http://www.hive13.org/?feed=rss2"));

			addCommandThread(new jIBCTRssReader(
					this,
					"h13List",
					channelList.get(0),
					"[commandName]: [Title|c50] ~[Author|c20|r\\(.+\\)] ([Link])",
					"http://groups.google.com/group/cincihackerspace/feed/rss_v2_0_msgs.xml"));

			// PTV: Flickr feed has been known to have issues...
			addCommandThread(new jIBCTRssReader(
					this,
					"Flickr",
					channelList.get(0),
					"[commandName]: [Title|c50] ([Link])",
					"http://api.flickr.com/services/feeds/photos_public.gne?tags=hive13&lang=en-us&format=rss_200",
					15 * 60 * 1000)); // 15 minutes (15 * 60 seconds)

			addCommandThread(new jIBCTRssReader(
					this,
					"Tweet",
					channelList.get(0),
					"[commandName]: [Title|c30] ~[Author|c20|r\\(.+\\)] ([Link])",
					"http://search.twitter.com/search.atom?q=hive13"));		
		} catch (MalformedURLException ex) {
			Logger.getLogger(jIRCBot.class.getName()).log(Level.SEVERE, null,
					ex);
			log("jIRCBot()" + ex.toString(), eLogLevel.severe);
		}

		// Connect to IRC
		setAutoNickChange(true);
		setName(botName);
		try {
			// Connect to the config server
			connect(serverAddress);

			// Connect to all channels listed in the config.
			for (Iterator<String> i = channelList.iterator(); i.hasNext();) {
				joinChannel(i.next());
			}
		} catch (NickAlreadyInUseException ex) {
			Logger.getLogger(jIRCBot.class.getName()).log(Level.SEVERE, null,
					ex);
			this.log("Error: jIRCBot()" + ex.toString());
		} catch (IrcException ex) {
			Logger.getLogger(jIRCBot.class.getName()).log(Level.SEVERE, null,
					ex);
			this.log("Error: jIRCBot()" + ex.toString());
		} catch (IOException ex) {
			Logger.getLogger(jIRCBot.class.getName()).log(Level.SEVERE, null,
					ex);
			this.log("Error: jIRCBot()" + ex.toString());
		}
	}

	public void onMessage(String channel, String sender, String login,
			String hostname, String message) {
		jIRCTools.insertMessage(channel, this.getServer(), sender, message,
				eMsgTypes.publicMsg);
		// Find out if the message was directed as a command.
		if (message.startsWith(prefix)) {
			message = message.replace(prefix, "");

			jIBCommand cmd;
			// Check to see if it is a known standard command.
			String[] sCmd = message.split(" ", 2);
			if ((cmd = commands.get(sCmd[0].toLowerCase())) != null
					|| (cmd = commands.get(sCmd[0].toLowerCase() + channel)) != null)
				if (sCmd.length == 2)
					cmd.runCommand(this, channel, sender, sCmd[1].trim());
				else
					cmd.runCommand(this, channel, sender, "");
		}

		/*
		 * Run the commands that run with every message It is especially
		 * important to make sure that these commands are thread safe. All
		 * commands are run as new threads, however these commands have a
		 * greater potential to still be running if several messages come in
		 * fast.
		 */
		Iterator<jIBCommand> i = lineParseCommands.iterator();
		while (i.hasNext()) {
			i.next().runCommand(this, channel, sender, message);
		}
	}

	/*
	 * If the bot is disconnected from the server, quit the bot.
	 */
	@Override
	public void onDisconnect() {
		if (abShouldQuit.get()) {
			log("We are shutting down properly!", eLogLevel.info);
			System.exit(0);
		} else {
			log("Accidental disconnect detected, initiating reconnect.",
					eLogLevel.warning);
			try {
				// Connect to the config server
				connect(serverAddress);

				// Connect to all channels listed in the config.
				for (Iterator<String> i = channelList.iterator(); i.hasNext();) {
					joinChannel(i.next());
				}
			} catch (NickAlreadyInUseException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (IrcException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Various log levels for when the bot is writing to the log. This is
	 * primarily used for formatting the log message.
	 * 
	 * @author vincentp
	 * 
	 */
	public enum eLogLevel {
		/** Just an alert message giving a bot status update. */
		info,
		/**
		 * Either a minor error or slightly unexpected event has occurred. this
		 * is not really serious and the bot will continue normal operations
		 */
		warning,
		/**
		 * A more serious event has occurred. The bot will probably recover from
		 * this error and continue normal operations, however it is not
		 * guaranteed.
		 */
		error,
		/**
		 * A major error has occurred that will effect bot actions and
		 * operations. This should never occur in the course of bot operation
		 * unless it has been incorrectly configured or compiled. The bot can
		 * not continue normal operations and will probably act very strangely
		 * until it is restarted. This error is thrown when a key process is
		 * interrupted or throws an error.
		 */
		severe
	}

	/**
	 * This function uses the logLevel to determine how to treat each message
	 * that is passed to it.
	 * 
	 * @param line
	 *            Line to write to the log.
	 * @param logLevel
	 *            The level of importance to give the message.
	 */
	public void log(String line, eLogLevel logLevel) {
		switch (logLevel) {
		case info:
			line = "<Info> " + line;
			break;
		case warning:
			line = "<Warning> " + line;
			break;
		case error:
			line = "<Error> " + line;
			break;
		case severe:
			line = "<Severe Error> " + line;
			break;
		}

		super.log(line);
	}

	/**
	 * Checks to see if the bot is actively in the specified channel.
	 * 
	 * @param channel
	 *            Name of the channel to check for.
	 * @return Returns true if the bot is in the channel.
	 */
	public boolean inChannel(String channel) {
		String channelList[] = this.getChannels();
		for (String _channel : channelList) {
			if (_channel.equals(channel))
				return true;
		}
		return false;
	}

	// @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
	// The following is only used for message logging purposes right now.
	public void onAction(String sender, String login, String hostname,
			String target, String action) {
		jIRCTools.insertMessage(target, this.getServer(), sender, action,
				eMsgTypes.actionMsg);
	}

	public void onUserList(String channel, User[] users) {
		// This function is called whenever we join a channel, or whenever we
		// initiate
		// a "ListUsers" action for a channel.

		// Here we loop through the list of 'users' in the 'channel'.
		for (int i = 0; i < users.length; i++) {
			jIRCUser user;
			// If we already have an account of this user in another channel...
			if ((user = userListGet(users[i].getNick())) != null) {
				// Update that user to also have this channel.
				// The jIRCUser class is responsible for ensuring there are no
				// duplicates.
				user.addChannel(channel);
			} else {
				// The user was not found in our list of known users.
				// So we add it here, then add the current channel
				// to the list of channels the user is in.
				user = new jIRCUser(users[i].getNick());
				user.addChannel(channel);
				userListPut(user);
				
				// Start Auth for the user
				startAuthForUser(user);
			}
		}
	}

	public void onJoin(String channel, String sender, String login,
			String hostname) {
		// This function is called whenever a user joins a channel we are also
		// in.
		// This function is also called when WE join the channel.

		// Do we know about this user?
		if (sender.equals(this.getNick())) {
			// We just joined a channel, get the list of users in this channel.
			// *UPDATE* This does not work... see the "OnUserList" method.
		} else {
			// This is someone else joining the channel.
			jIRCUser user;
			if ((user = userListGet(sender)) != null) {
				// Existing user, update it.
				user.addChannel(channel);
			} else {
				// New user, add it.
				user = new jIRCUser(sender);
				user.addChannel(channel);
				userListPut(user);
			}

			// Initiate check for credentials
			startAuthForUser(user);
			
			// Write the event to the log.
			jIRCTools.insertMessage(channel, this.getServer(), sender, "",
					eMsgTypes.joinMsg);
		}
	}

	public void onPart(String channel, String sender, String login,
			String hostname) {
		// This function is called when a user leaves a channel we are in.
		// This function is also called when WE leave a channel.

		// Did we just leave the channel?
		if (sender.equals(this.getNick())) {
			// Yes, remove the list of users we know in this channel.
			// ** NOTE ** This is going to be horribly in-efficient.
			// We need to get each user, then go through
			// each user's list of channels and remove the
			// channel from that list.
			// So I think that makes this take about n^x where we
			// we have n users and x channels.
			// The one saving grace of this method is that while 'n' might
			// be large, 'x' should be small, and in most cases only 1.
			Iterator<Entry<String, jIRCUser>> it = userListEntrySet()
					.iterator();
			while (it.hasNext()) {
				Entry<String, jIRCUser> e = it.next();
				jIRCUser user = e.getValue();
				// Remove any channel with the name in this list.
				user.removeChannel(channel);

				// If that was the only channel, remove the item.
				if (user.getChannelCount() == 0)
					userListRemove(e.getKey());
			}
		} else {
			// No, it was someone else.
			jIRCUser user;
			if ((user = userListGet(sender)) != null) {
				user.removeChannel(channel);

				// Was this the only channel the user as in?
				if (user.getChannelCount() == 0)
					userListRemove(sender);
			}

			// Log this user leaving the channel.
			jIRCTools.insertMessage(channel, this.getServer(), sender, "",
					eMsgTypes.partMsg);
		}
	}

	public void onQuit(String sourceNick, String sourceLogin,
			String sourceHostname, String reason) {
		// This is a User quitting the server entirely.
		// This could be US quitting the server entirely.

		// This could be us, but if we quit, who cares?
		// Find the user that is leaving.
		jIRCUser user;
		if ((user = userListRemove(sourceNick)) != null) {
			// Then log the user quitting in all channels that the user was in.
			Iterator<String> i = user.getChannelIterator();
			while (i.hasNext()) {
				jIRCTools.insertMessage(i.next(), this.getServer(), sourceNick,
						reason, eMsgTypes.quitMsg);
			}
		}
	}

	public void onNickChange(String oldNick, String login, String hostname,
			String newNick) {
		// This is a User changing their Nick.
		// This could be US changing our Nick, but who cares if we change our
		// nick?

		// Remove the old username from the list of known users.
		jIRCUser user;
		if ((user = userListRemove(oldNick)) != null) {
			// Log the change in name.
			Iterator<String> i = user.getChannelIterator();
			while (i.hasNext()) {
				jIRCTools.insertMessage(i.next(), this.getServer(), oldNick,
						newNick, eMsgTypes.nickChange);
			}
			// Change the users name, and re-add him to the list of users.
			user.setUsername(newNick);
			userListPut(user);
		}
	}

	/*
	 * Ok, time to look into how to authorize users again. Here is the basic
	 * sequence of steps that need to occur: 1. Send "info <Username>" message
	 * to 'nickserv' user. 2. Parse returned response from 'nickserv' user to
	 * determine if the user is indeed authorized.
	 * 
	 * Perhaps best to try to do the 'Auth' requests as users are created, then
	 * just act as if the requests have gone through?
	 * 
	 * This presents the potential that multiple threads will be accessing and
	 * mutating the 'userList' variable. This may require that I encapsulate
	 * accessor & mutator methods for it.
	 */
	public void startAuthForUser(jIRCUser user) {
		// Essentially sends the following to the nickserv bot:
		// /msg nickserv info TargetUsername
		this.sendMessage(jIRCProperties.getInstance().getNickServUsername(),
				"info " + user.getUsername());
		
	}

	// I am reasonably sure that this function will not be called asynchronously
	// However I am not certain.
	private jIRCUser targetUser = null;
	private boolean targetUserLoggedIn = false;
	private eAuthLevels targetPendingAuthLevel = eAuthLevels.unauthorized;
	
	public void onNotice(String sourceNick, String sourceLogin, 
			String sourceHostname, String target, String notice) {
		/*
		 * Messages will be returned from the nickserv in groups.
		 * 
		 * Each group will be for a user.
		 * 
		 * 	Information on peters-tx (account peters-tx):
			Registered : Dec 27 15:31:03 2006 (3 years, 40 weeks, 6 days, 03:49:20 ago)
			Last addr  : ~p@dsl081-112-039.dfw1.dsl.speakeasy.net
			Last seen  : now
			Flags      : HideMail
			*** End of Info ***
		 */
		if(sourceNick.equalsIgnoreCase(jIRCProperties.getInstance().getNickServUsername())) {
			// Check if the response is w/ regards to a user I asked about.
			if(notice.startsWith("Information")) {
				// Find the user referred to in this response.
				int indexOfUsername = notice.lastIndexOf(" ") + 2;
				String username = notice.substring(indexOfUsername, notice.length() - 3);
				
				boolean inOpList = false;
				boolean inAdminList = false;
				
				if((inOpList = jIRCProperties.getInstance().getOpUserList().contains(username.toLowerCase())) ||
			       (inAdminList = jIRCProperties.getInstance().getAdminUserList().contains(username.toLowerCase()))) {
					int endIndexOfNick = notice.indexOf(" (") - 1;
					String nick = notice.substring(16, endIndexOfNick);
					targetUser = userListGet(nick);	
					if(inOpList)
					    targetPendingAuthLevel = eAuthLevels.operator;
					else if (inAdminList)
					    targetPendingAuthLevel = eAuthLevels.admin;
				}
				// Set a flag indicating what user we are currently parsing.
			} else if(notice.startsWith("Last seen") && targetUser != null) { // and we are currently parsing
			    // Make sure it is set to "Now"
				String isLoggedInNow = notice.substring(13);
				targetUserLoggedIn = isLoggedInNow.equals("now");
				if(targetUserLoggedIn) {
					targetUser.setAuthorized(targetPendingAuthLevel);
					log("Just Auth'ed " + targetUser.getUsername() +
					        " with level " + targetPendingAuthLevel, eLogLevel.info);
					
					targetUser = null;
				}
			} else if(notice.startsWith("***")) {
				// End parsing, Validate User credentials.
				targetUser = null;
			}
		}
	}

	// @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
	// Utility functions for member variables.
	/**
	 * Adds a command to the list of known commands.
	 * 
	 * @param cmd
	 *            Command to add to the command list.
	 */
	public void addCommand(jIBCommand cmd) {
		commands.put(cmd.getCommandName().toLowerCase(), cmd);
	}

	/**
	 * Adds a commandThread to the list of known commands.
	 * 
	 * @param cmd
	 *            CommandThread to add to the command list.
	 */
	public void addCommandThread(jIBCommandThread cmd) {
		commands.put(cmd.getCommandName().toLowerCase(), cmd);
	}

	/**
	 * Adds a command that parses each line received.
	 * 
	 * @param cmd
	 *            Command to add to the line parsing command.
	 */
	public void addLineParseCommand(jIBCommand cmd) {
		lineParseCommands.add(cmd);
	}

	/**
	 * Safely returns the jIRCUser associated with the passed in username from
	 * the userList.
	 * 
	 * @param username
	 *            Key for the userList variable.
	 * @return The jIRCUser associated with the passed in username.
	 */
	public jIRCUser userListGet(String username) {
		jIRCUser result = null;
		try {
			userListMutex.acquire();
			result = userList.get(username.toLowerCase());
		} catch (InterruptedException e) {
			Logger.getLogger(jIRCBot.class.getName())
					.log(Level.SEVERE, null, e);
			e.printStackTrace();
		} finally {
			userListMutex.release();
		}

		return result;
	}

	/**
	 * Safely adds the passed in user to the userList.
	 * 
	 * @param user
	 *            jIRCUser to add to the userList.
	 */
	public void userListPut(jIRCUser user) {
		try {
			userListMutex.acquire();
			userList.put(user.getUsername().toLowerCase(), user);
		} catch (InterruptedException e) {
			Logger.getLogger(jIRCBot.class.getName())
					.log(Level.SEVERE, null, e);
			e.printStackTrace();
		} finally {
			userListMutex.release();
		}
	}

	/**
	 * Safely wraps the userList.remove(String) method.
	 * 
	 * @param username
	 *            Username of the user to remove.
	 * @return The removed jIRCUser.
	 */
	public jIRCUser userListRemove(String username) {
		jIRCUser result = null;
		try {
			userListMutex.acquire();
			result = userList.remove(username.toLowerCase());
		} catch (InterruptedException e) {
			Logger.getLogger(jIRCBot.class.getName())
					.log(Level.SEVERE, null, e);
			e.printStackTrace();
		} finally {
			userListMutex.release();
		}

		return result;
	}

	/**
	 * Safely wraps the userList.EntrySet() method.
	 * 
	 * @return The set of Keys & entries for userList.
	 */
	public Set<Entry<String, jIRCUser>> userListEntrySet() {
		Set<Entry<String, jIRCUser>> result = null;
		try {
			userListMutex.acquire();
			result = userList.entrySet();
		} catch (InterruptedException e) {
			Logger.getLogger(jIRCBot.class.getName())
					.log(Level.SEVERE, null, e);
			e.printStackTrace();
		} finally {
			userListMutex.release();
		}
		return result;
	}
}