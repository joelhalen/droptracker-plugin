
# DropTracker

Shares your drops with the [DropTracker](https://www.droptracker.io/) database.
[Website](https://www.droptracker.io/) | [Discord](https://www.droptracker.io/discord) | [Docs](https://www.droptracker.io/docs)
![image](https://github.com/joelhalen/droptracker-plugin/assets/128320003/b1c0ddb1-fab8-47a4-8af6-df6ab220060c)

**Features**:

 1. Real-time tracking of any drop you receive.
 2. Global "loot leaderboards"
 3. Discord notifications
 4. Full integration for clan-specific leaderboards


**[Setting up (click to visit our docs)](https://www.droptracker.io/docs)**
## Using the API functionality (preferred):
Our API enables an enhanced level of authentication and allows us to run events both globally for all players using our plugin, and inside of individual clan Discord servers.

If you choose not to use the API, you will be **ineligible** for any DropTracker-hosted events.

*Note: requires a discord server with the DropTracker bot invited & configured in order to work properly!*

 1. Toggle `Make external connections` **on** in the plugin configuration.
 2. Register using your in-game name inside your clan's Discord server
 3. Enter the provided verification key into your plugin config. (either DMed to you or available through `/gettoken`  in the Discord you registered.
 4. Refresh the panel!

![image](https://github.com/joelhalen/droptracker-plugin/assets/128320003/ed087120-2636-4bd6-89e7-8140ee834f74)
## Using Google Spreadsheets/Discord Webhooks


We've also developed a way for players unwilling to register on our API to track their drops.

 1. Download the plugin
 2. Create a fresh, new Google Sheet.
 3. Invite the DropTracker Google account to your spreadsheet `as an editor`. The Google account is: **`minecraftvitality@gmail.com`**
 5. Copy the **ID** of your spreadsheet. Example: ![image](https://github.com/joelhalen/droptracker-plugin/assets/128320003/80c1d95f-ac4e-473e-9b91-cf46913708ff)

 6. **Do not create a 'drops' sheet until the bot does so for you.**
 7. Insert the Spreadsheet ID into your plugin configuration, toggling screenshots on if you want to them sent to our database.
That's all! Once you start receiving drops with a valid sheet URL configured, you should see your drops appear.
