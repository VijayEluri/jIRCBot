package org.hive13.jircbot.commands;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.Calendar;

import javax.net.ssl.SSLHandshakeException;

import org.hive13.jircbot.jIRCBot;
import org.hive13.jircbot.support.MtGoxTicker;
import org.hive13.jircbot.support.jIRCTools;

import com.google.gson.Gson;

public class jIBCBitcoin extends jIBCommand {
   private final String MT_GOX_URL = "https://mtgox.com/code/data/ticker.php";
   private Calendar lastDisplayed = null;
      
        @Override
        public String getCommandName() {
                return "bitcoin";
        }

        @Override
        public String getHelp() {
                return "";
        }

        @Override
        protected void handleMessage(jIRCBot bot, String channel, String sender,
                        String message) {
           if(message.toLowerCase().equals(getCommandName())) {
              sendBitcoinUpdate(bot, channel);
           } else if(message.contains(getCommandName())) {
              if(lastDisplayed != null) {
                 long now = Calendar.getInstance().getTimeInMillis();
                 if(now - lastDisplayed.getTimeInMillis() > 600000) { // 10 Minutes
                    sendBitcoinUpdate(bot, channel);
                 }
              } else {
                 sendBitcoinUpdate(bot, channel);
              }
           }
      }
        
        private void sendBitcoinUpdate(jIRCBot bot, String channel) {
           Object content = null;
           Gson gson = new Gson();
           String errorMsg = "";
           try {
            content = jIRCTools.getUrlContent(MT_GOX_URL);
         } catch (MalformedURLException e) {
            errorMsg = "Mt. Gox is reporting an invalid URL.";
            e.printStackTrace();
         } catch (SocketTimeoutException e) {
            errorMsg = "Mt. Gox appears to be down right now";
            e.printStackTrace();
         } catch (SSLHandshakeException e) {
            errorMsg = "Mt. Gox gave us an SSL Handshake error.";
            e.printStackTrace();
         } catch (IOException e) {
            errorMsg = "Mt. Gox had an IOException? Seriously?";
            e.printStackTrace();
         }

           if(content != null) {
                 String json = content.toString();
                 // Massage json to fit our class a bit
                 json = json.substring(10, json.lastIndexOf('}'));
                 MtGoxTicker ticker = gson.fromJson(json, MtGoxTicker.class);
                 bot.sendMessage(channel, "Mt. Gox: Last=" + ticker.last + " High=" + ticker.high + " Low=" + ticker.low);
                 lastDisplayed = Calendar.getInstance();
           } else {
                 bot.sendMessage(channel, errorMsg);
           }      
        }
}
