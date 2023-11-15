package com.joelhalen.droptracker;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.inject.Inject;

public class GroupMemberClient {
    @Inject
    private OkHttpClient client;

    public String[] getGroupMembers(Long serverId, String playerName) throws IOException {
        // Update this URL to point to your backend API endpoint
        String groupUrl = "http://droptracker.io/admin/api/get_users_by_server_id.php?server_id=" + serverId;
        Request request = new Request.Builder().url(groupUrl).build();

        Response response = client.newCall(request).execute();
        String responseBody = Objects.requireNonNull(response.body()).string();

        // Parse the response based on your backend API's response structure
        JSONArray users = new JSONArray(responseBody);

        List<String> usernames = new ArrayList<>();
        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.getJSONObject(i);
            // Assuming 'rsn' is the key for username in your JSON response
            String rsn = user.getString("rsn");
            if (!rsn.equalsIgnoreCase(playerName)) {
                usernames.add(user.getString("rsn"));
            }
        }

        return usernames.toArray(new String[0]);
    }
}