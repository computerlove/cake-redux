package no.javazone.cake.redux.comments;

public enum FeedbackType {
    COMMENT,TALK_RATING;

    Feedback.FeedbackBuilder builder() {
        switch (this) {
            case COMMENT:
                return Comment.builder();
            case TALK_RATING:
                return TalkRating.builder();
            default:
                throw new RuntimeException("Unknown feedback type " + this);
        }
    }
}
