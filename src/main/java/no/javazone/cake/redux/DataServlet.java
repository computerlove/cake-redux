package no.javazone.cake.redux;

import no.javazone.cake.redux.comments.FeedbackService;
import no.javazone.cake.redux.mail.MailSenderImplementation;
import no.javazone.cake.redux.mail.MailSenderService;
import no.javazone.cake.redux.sleepingpill.SleepingpillCommunicator;
import no.javazone.cake.redux.sleepingpill.SlotUpdaterService;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.jsonbuddy.*;
import org.jsonbuddy.parse.JsonParser;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;

public class DataServlet extends HttpServlet {
    private SleepingpillCommunicator sleepingpillCommunicator;
    private AcceptorSetter acceptorSetter;
    private UserFeedbackCommunicator userFeedbackCommunicator;

    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String pathInfo = req.getPathInfo();
        if ("/editTalk".equals(pathInfo)) {
            updateTalk(req, resp);
        } else if ("/acceptTalks".equals(pathInfo)) {
            acceptTalks(req,resp);
        } else if ("/massUpdate".equals(pathInfo)) {
            massUpdate(req, resp);
        } else if ("/assignRoom".equals(pathInfo)) {
            assignRoom(req,resp);
        } else if ("/assignSlot".equals(pathInfo)) {
            assignSlot(req,resp);
        } else if ("/massPublish".equals(pathInfo)) {
            massPublish(req, resp);
        } else if ("/addComment".equals(pathInfo)) {
            addComment(req, resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/giveRating".equals(pathInfo)) {
            giveRating(req, resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/addPubComment".equals(pathInfo)) {
            addPublicComment(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/publishChanges".equals(pathInfo)) {
            publishChanges(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/updateroomslot".equals(pathInfo)) {
            updateRoomSlot(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/updateSlotLength".equals(pathInfo)) {
            updateSlotLength(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/readSlotForUpdate".equals(pathInfo)) {
            readSlotForUpdate(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if ("/sendForRoomSlotUpdate".equals(pathInfo)) {
            roomSlotUpdate(req,resp);
            resp.setContentType("application/json;charset=UTF-8");
        } else if("/emailDraftSubmitters".equals(pathInfo)) {
            String eventId = req.getParameter("eventId");
            emailDraftSubmitters(eventId);
        }

    }

    private void roomSlotUpdate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        SlotUpdaterService.get().updateRoomSlot(update,computeAccessType(req));
        JsonFactory.jsonObject().toJson(resp.getWriter());
    }

    private void readSlotForUpdate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        JsonObject result = SlotUpdaterService.get().readDataOnTalks(update,computeAccessType(req));
        result.toJson(resp.getWriter());
    }

    private void updateRoomSlot(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        String talkref = update.requiredString("talkref");
        UserAccessType userAccessType = computeAccessType(req);
        Optional<String> startTime = update.stringValue("starttime");
        if (startTime.isPresent()) {
            sleepingpillCommunicator.updateSlotTime(talkref,startTime.get(), userAccessType);
        }
        Optional<String> room = update.stringValue("room");
        if (room.isPresent()) {
            sleepingpillCommunicator.updateRoom(talkref,room.get(),userAccessType);
        }

        JsonFactory.jsonObject().toJson(resp.getWriter());
    }

    private void updateSlotLength(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        String talkref = update.requiredString("talkref");
        UserAccessType userAccessType = computeAccessType(req);
        sleepingpillCommunicator.updateSlotLength(talkref, (int) update.requiredLong("length"), userAccessType);
    }

    private void publishChanges(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        sleepingpillCommunicator.pubishChanges(update.requiredString("talkref"),computeAccessType(req));
        JsonFactory.jsonObject().toJson(resp.getWriter());
    }

    private void addPublicComment(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }
        String ref = update.requiredString("talkref");
        String comment = update.requiredString("comment");
        String lastModified = update.requiredString("lastModified");
        JsonObject jsonObject = sleepingpillCommunicator.addPublicComment(ref, comment, lastModified);

        SimpleEmail simpleEmail = generateCommentEmail(jsonObject);
        MailSenderService.get().sendMail(MailSenderImplementation.create(simpleEmail));


        JsonArray updatedComments = jsonObject.requiredArray("comments");
        updatedComments.toJson(resp.getWriter());




    }

    private SimpleEmail generateCommentEmail(JsonObject jsonObject) {
        SimpleEmail simpleEmail = new SimpleEmail();
        try {
            jsonObject.requiredArray("speakers").objectStream()
                    .map(ob -> ob.requiredString("email"))
                    .forEach(to -> {
                        try {
                            simpleEmail.addTo(to);
                        } catch (EmailException e) {
                            throw new RuntimeException(e);
                        }
                    });
            AcceptorSetter.setupMailHeader(simpleEmail,"Regarding your Trondheim Developer Conference submission");
            simpleEmail.setMsg("Hello,\n\n" +
                    "The program committee has added a comment to your submission that requires your attention. " +
                    "Please head to https://submitit.trondheimdc.no to see the comment.\n" +
                    "\nRegards\nThe Trondheim Developer Conference program comittee");
        } catch (EmailException e) {
            throw new RuntimeException(e);
        }
        return simpleEmail;
    }

    private void giveRating(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonArray ratings = FeedbackService.get().giveRating(JsonParser.parseToObject(req.getInputStream()), req.getSession().getAttribute("username").toString());
        ratings.toJson(resp.getWriter());
    }

    private void addComment(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonArray comments = FeedbackService.get()
                .addComment(JsonParser.parseToObject(req.getInputStream()), req.getSession().getAttribute("username").toString());
        comments.toJson(resp.getWriter());
    }

    private void massPublish(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try (InputStream inputStream = req.getInputStream()) {
            JsonObject update = JsonParser.parseToObject(inputStream);
            String ref = update.requiredString("ref");
            approveTalk(ref,computeAccessType(req));
        }
        JsonObject objectNode = JsonFactory.jsonObject();
        objectNode.put("status","ok");
        resp.setContentType("text/json");
        objectNode.toJson(resp.getWriter());
    }

    private void approveTalk(String ref,UserAccessType userAccessType) throws IOException {
        sleepingpillCommunicator.approveTalk(ref,userAccessType);
    }



    private void assignRoom(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        throw new NotImplementedException();
        /*
        try (InputStream inputStream = req.getInputStream()) {
            String inputStr = CommunicatorHelper.toString(inputStream);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode update = objectMapper.readTree(inputStr);
            String ref = update.get("talkRef").asText();
            String roomRef = update.get("roomRef").asText();

            String lastModified = update.get("lastModified").asText();

            //String newTalk = xemsCommunicator.assignRoom(ref,roomRef,lastModified,computeAccessType(req));
            //resp.getWriter().append(newTalk);
        }*/

    }

    private void assignSlot(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        throw new NotImplementedException();
        /*
        try (InputStream inputStream = req.getInputStream()) {
            String inputStr = CommunicatorHelper.toString(inputStream);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode update = objectMapper.readTree(inputStr);

            String ref = update.get("talkRef").asText();
            String slotRef = update.get("slotRef").asText();

            String lastModified = update.get("lastModified").asText();

            String newTalk = emsCommunicator.assignSlot(ref, slotRef, lastModified,computeAccessType(req));
            resp.getWriter().append(newTalk);
        }*/

    }




    private static UserAccessType computeAccessType(HttpServletRequest request) {
        String fullusers = Optional.ofNullable(Configuration.fullUsers()).orElse("");
        if (Optional.ofNullable(request.getSession().getAttribute("username"))
           // .filter(un -> fullusers.contains((String) un))
            .isPresent()) {
            return UserAccessType.FULL;
        }
        return UserAccessType.WRITE;
    }

    private void acceptTalks(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try (InputStream inputStream = req.getInputStream()) {
            JsonObject obj = JsonParser.parseToObject(inputStream);
            JsonArray talks = obj.requiredArray("talks");
            String inputStr = CommunicatorHelper.toString(inputStream);

            String statusJson = acceptorSetter.accept(talks,computeAccessType(req));
            resp.getWriter().append(statusJson);
        }
    }

    private void massUpdate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try (InputStream inputStream = req.getInputStream()) {
            JsonObject input = JsonParser.parseToObject(inputStream);
            String statusJson = acceptorSetter.massUpdate(input,computeAccessType(req));
            resp.getWriter().append(statusJson);

        }
    }
    private void updateTalk(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonObject update;
        try (InputStream inputStream = req.getInputStream()) {
            update = JsonParser.parseToObject(inputStream);
        }

        String ref = update.requiredString("ref");
        JsonArray tags = (JsonArray) update.value("tags").orElse(JsonFactory.jsonArray());
        JsonArray keywords = (JsonArray) update.value("keywords").orElse(JsonFactory.jsonArray());

        String state = update.requiredString("state");
        String lastModified = update.stringValue("lastModified").orElse("xx");

        List<String> taglist = unique(tags.stringStream());
        List<String> keywordlist = unique(keywords.stringStream());

        //String newTalk = emsCommunicator.update(ref, taglist, state, lastModified,computeAccessType(req));
        String newTalk = sleepingpillCommunicator.update(ref, taglist, keywordlist,state, lastModified,computeAccessType(req));
        resp.getWriter().append(newTalk);

    }

    private List<String> unique(Stream<String> items) {
        return items.map(String::toLowerCase)
                .distinct()
                .collect(Collectors.toList());
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        String pathInfo = request.getPathInfo();
        if ("/talks".equals(pathInfo)) {
            String encEvent = request.getParameter("eventId");
            String json = JsonArray.fromNodeStream(sleepingpillCommunicator.talkShortVersion(encEvent)
                    .nodeStream()
                    .map(JsonObject.class::cast)
                    .map(this::appendRatings).map(JsonNode.class::cast))
                    .toJson();

            writer.append(json);

        } else if ("/atalk".equals(pathInfo)) {
            String encTalk = request.getParameter("talkId");
            JsonObject oneTalkAsJson = sleepingpillCommunicator.oneTalkAsJson(encTalk);
            appendFeedbacks(oneTalkAsJson,encTalk);
            String conferenceId = oneTalkAsJson.requiredString("conferenceId");
            appendUserFeedback(oneTalkAsJson, userFeedbackCommunicator.feedback(conferenceId, encTalk));
            writer.append(SleepingpillCommunicator.jsonHackFix(oneTalkAsJson.toJson()));
        } else if ("/events".equals(pathInfo)) {
            writer.append(sleepingpillCommunicator.allEvents());
        } else if ("/roomsSlots".equals(pathInfo)) {
            String encEvent = request.getParameter("eventId");
            JsonFactory.jsonObject()
                    .put("rooms",JsonFactory.jsonArray())
                    .put("slots",JsonFactory.jsonArray())
                    .toJson(writer);
            //writer.append(emsCommunicator.allRoomsAndSlots(encEvent));
        } else if ("/draftemails".equals(pathInfo)) {
            Set<String> draftSubmitters = new FindDrafts().analyze(request.getParameter("eventId"));
            JsonFactory.jsonArray()
                    .addAll(new ArrayList<>(draftSubmitters))
                    .toJson(writer);
        }
    }


    private void emailDraftSubmitters(String eventId) {
        System.out.println("emailDraftSubmitters " + eventId);
        Collection<String> draftSubmitters = new FindDrafts().analyze(eventId);
        for (String draftSubmitter : draftSubmitters) {
            System.out.println("emailing draft submitter " + draftSubmitter);
            try {
                SimpleEmail simpleEmail = new SimpleEmail();
                simpleEmail.addTo(draftSubmitter);
                AcceptorSetter.setupMailHeader(simpleEmail,"Have you forgotten to submit your proposal for Trondheim Developer Conference 2019?");
                simpleEmail.setMsg("Hello,\n\n" +
                        "We have started evaluating all submissions. " +
                        "On this occasion, we have noticed that you have a submission that is still registered as a draft. \n" +
                        "If you did not intend to submit this proposal, please ignore this message. \n" +
                        "Otherwise, please log into https://submitit.trondheimdc.no and change your submission status as soon as possible such that we can consider your talk.\n\n" +
                        "If you have any questions do not hesitate to contact us at connect@trondheimdc.no\n" +
                        "\n" +
                        "Thank you, the Trondheim Developer Conference program committee");
                MailSenderService.get().sendMail(MailSenderImplementation.create(simpleEmail));
            } catch (EmailException e) {
                e.printStackTrace();
            }
        }

    }

    private void appendUserFeedback(JsonObject oneTalkAsJson, Optional<String> feedback) {
        if (feedback.isPresent()) {
            JsonObject feedbackAsJson = JsonParser.parseToObject(feedback.get());
            oneTalkAsJson.put("userFeedback", feedbackAsJson);
        } else {
            oneTalkAsJson.put("userFeedback", new JsonNull());
        }
    }

    private void appendFeedbacks(JsonObject oneTalkAsJson, String encTalk) {
        JsonArray comments = FeedbackService.get().commentsForTalk(encTalk);
        oneTalkAsJson.put("comments",comments);
        JsonArray ratings = FeedbackService.get().ratingsForTalk(encTalk);
        oneTalkAsJson.put("ratings",ratings);
        Optional<String> contact = FeedbackService.get().contactForTalk(encTalk);
        oneTalkAsJson.put("contactPhone",contact.orElse("Unknown"));
    }

    private JsonObject appendRatings(JsonObject oneTalkAsJson) {
        JsonArray ratings = FeedbackService.get()
                .ratingsForTalk(oneTalkAsJson.requiredString("ref"));
        oneTalkAsJson.put("ratings",ratings);
        return oneTalkAsJson;
    }
    

    @Override
    public void init() throws ServletException {
        sleepingpillCommunicator = new SleepingpillCommunicator();
        userFeedbackCommunicator = new UserFeedbackCommunicator();
        acceptorSetter = new AcceptorSetter(sleepingpillCommunicator, userFeedbackCommunicator);
    }


    public void setUserFeedbackCommunicator(UserFeedbackCommunicator userFeedbackCommunicator) {
        this.userFeedbackCommunicator = userFeedbackCommunicator;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            super.service(req, resp);
        } catch (NoUserAceessException ex) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,"User do not have write access");
        }
    }

    public DataServlet setSleepingpillCommunicator(SleepingpillCommunicator sleepingpillCommunicator) {
        this.sleepingpillCommunicator = sleepingpillCommunicator;
        return this;
    }
}
