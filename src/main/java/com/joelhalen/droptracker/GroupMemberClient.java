/*      BSD 2-Clause License

		Copyright (c) 2023, joelhalen

		Redistribution and use in source and binary forms, with or without
		modification, are permitted provided that the following conditions are met:

		1. Redistributions of source code must retain the above copyright notice, this
		list of conditions and the following disclaimer.

		2. Redistributions in binary form must reproduce the above copyright notice,
		this list of conditions and the following disclaimer in the documentation
		and/or other materials provided with the distribution.

		THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
		AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
		IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
		DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
		FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
		DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
		SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
		CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
		OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
		OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.     */
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
        String groupUrl = "https://droptracker.io/admin/api/get_users_by_server_id.php?server_id=" + serverId;
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