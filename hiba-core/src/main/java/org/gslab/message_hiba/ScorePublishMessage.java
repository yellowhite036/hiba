package org.gslab.message_hiba;

public class ScorePublishMessage extends Message {

    private String Name = "";
    private float Score = 0;

    public ScorePublishMessage(String Name, float Score) {
        this.Name = Name;
        this.Score = Score;
    }

    public String getName() {
        return Name;
    }

    public float getScore() {
        return Score;
    }
}
