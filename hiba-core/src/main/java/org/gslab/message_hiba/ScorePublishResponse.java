package org.gslab.message_hiba;

public class ScorePublishResponse extends Message {

    private float Score = 0;

    public ScorePublishResponse(float Score) {
        this.Score = Score;
    }

    public float getScore() {
        return Score;
    }
}
