
The DropTracker RuneLite plugin is intended to be used in sync with the Discord bot, and by players in servers who have a loot leaderboard set up.

The plugin uses Discord webhooks alongside PHP requests to deliver a complete solution for clan events, clan-wide loot tracking metrics (coming soon), etc.

**Features**:
Automatic generation of verification keys for each user if they exist in your server:
![image](https://github.com/joelhalen/droptracker-plugin/assets/128320003/cfced6b0-5033-48b1-9f0f-9eeb63694b35)

![image](https://github.com/joelhalen/droptracker-plugin/assets/128320003/a7ed9529-58b3-4b84-b793-ce5648782c9a)


_This means that drops with an non-matching verification key, or from a user not registered, will be **completely ignored** by the clan's Loot Leaderboard!_


Automated tracking of all drops received:

![image](https://github.com/joelhalen/droptracker-plugin/assets/128320003/7dea5f8c-27d2-4c85-8a74-575fb168cf9b)

Automatic split tracking, with a list of clan members populated via clan WiseOldMan groups:

https://cdn.discordapp.com/attachments/691682908997681212/1117757640844521472/image.png

Completely automated _Random Item Hunts_, which allow random items to be assigned as tasks for players to obtain, with optional rewards provided:

![image](https://github.com/joelhalen/droptracker-plugin/assets/128320003/bd28c64f-d39d-4998-92a8-93c78c157f18)



This can easily be used as for bingo, etc, with the verification keys used as a way of confirmation. The discord bot which receives and handles these webhooks can automatically complete tasks based on what the player achieved.

