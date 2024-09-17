package io.droptracker.ui;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

class TableRow extends JPanel {
    Map<String, JLabel> labels = new HashMap<>();

    TableRow(String boss, String killsText, String rankText, String ehbText) {
        setLayout(new GridLayout(1, 4));
        addLabel(boss);
        addLabel(killsText);
        addLabel(rankText);
        addLabel(ehbText);
    }

    private void addLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        labels.put(text, label);
        add(label);
    }

    // Updates the row's labels with data
    public void update(String kills, String rank, String ehb) {
        labels.get("kills").setText(String.valueOf(kills));
        labels.get("rank").setText(String.valueOf(rank));
        labels.get("ehb").setText(String.valueOf(ehb));
    }

    // Resets the row to default state
    public void reset() {
        labels.get("kills").setText("--");
        labels.get("rank").setText("--");
        labels.get("ehb").setText("--");
    }
}

class RowPair {
    private final String skill;
    private final TableRow row;

    public RowPair(String skill, TableRow row) {
        this.skill = skill;
        this.row = row;
    }

    public String getSkill() {
        return skill;
    }

    public TableRow getRow() {
        return row;
    }
}
