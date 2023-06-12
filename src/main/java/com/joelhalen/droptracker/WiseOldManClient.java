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

public class WiseOldManClient {
    public static String[] getGroupMembers (int groupId) throws IOException {
        OkHttpClient client = new OkHttpClient();
        String groupUrl = "https://api.wiseoldman.net/v2/groups/" + groupId;
        Request request = new Request.Builder().url(groupUrl).build();

        Response response = client.newCall(request).execute();
        String responseBody = Objects.requireNonNull(response.body()).string();

        JSONObject jsonResponse = new JSONObject(responseBody);
        JSONArray memberships = jsonResponse.getJSONArray("memberships");

        List<String> displayNames = new ArrayList<>();
        for (int i = 0; i < memberships.length(); i++) {
            JSONObject membership = memberships.getJSONObject(i);
            JSONObject player = membership.getJSONObject("player");
            displayNames.add(player.getString("displayName"));
        }

        return displayNames.toArray(new String[0]);
    }
}