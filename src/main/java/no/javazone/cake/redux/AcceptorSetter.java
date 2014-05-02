package no.javazone.cake.redux;

import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class AcceptorSetter {
    private EmsCommunicator emsCommunicator;


    public AcceptorSetter(EmsCommunicator emsCommunicator) {
        this.emsCommunicator = emsCommunicator;
    }

    public String accept(JSONArray talks) {
        List<JSONObject> statusAllTalks = new ArrayList<>();
        for (int i=0;i<talks.length();i++) {
            JSONObject accept = new JSONObject();
            statusAllTalks.add(accept);
            try {
                String encodedTalkRef = talks.getJSONObject(i).getString("ref");
                JSONObject jsonTalk = new JSONObject(emsCommunicator.fetchOneTalk(encodedTalkRef));
                accept.put("title",jsonTalk.getString("title"));

                List<String> tags = toCollection(jsonTalk.getJSONArray("tags"));

                if (tags.contains("accepted")) {
                    accept.put("status","error");
                    accept.put("message","Talk is already accepted");
                    continue;
                }


                String talkType = talkTypeText(jsonTalk.getString("format"));
                SimpleEmail mail = setupMailHeader(talkType);

                String speakerName = addSpeakers(jsonTalk, mail);

                String submitLink = Configuration.submititLocation() + encodedTalkRef;
                String confirmLocation = Configuration.cakeLocation() + "confirm.html?id=" + encodedTalkRef;
                String title = jsonTalk.getString("title");

                String message = generateMessage(title, talkType, speakerName, submitLink, confirmLocation);
                mail.setMsg(message);
                mail.send();

                tags.add("accepted");
                String lastModified = jsonTalk.getString("last-modified");
                emsCommunicator.updateTags(encodedTalkRef,tags, lastModified);
                accept.put("status","ok");
                accept.put("message","ok");

            } catch (JSONException | EmailException e) {
                try {
                    accept.put("status","error");
                    accept.put("message","Error: " + e.getMessage());
                } catch (JSONException je) {
                    throw new RuntimeException(je);
                }
            }
        }
        return new JSONArray(statusAllTalks).toString();
    }

    private List<String> toCollection(JSONArray tags) throws JSONException {
        ArrayList<String> result = new ArrayList<>();
        if (tags == null) {
            return result;
        }
        for (int i=0;i<tags.length();i++) {
            result.add(tags.getString(i));
        }
        return result;
    }



    private SimpleEmail setupMailHeader(String talkType) throws EmailException {
        SimpleEmail mail = new SimpleEmail();
        mail.setHostName(Configuration.smtpServer());
        mail.setSmtpPort(Configuration.smtpPort());
        mail.setFrom("program@java.no", "Javazone program commitee");
        mail.addCc("program@java.no");
        mail.setSubject("Javazone 2014 " + talkType + " accepted");
        return mail;
    }

    private String addSpeakers(JSONObject jsonTalk, SimpleEmail mail) throws JSONException, EmailException {
        JSONArray jsonSpeakers = jsonTalk.getJSONArray("speakers");
        StringBuilder speakerName=new StringBuilder();
        for (int j=0;j<jsonSpeakers.length();j++) {
            JSONObject speaker = jsonSpeakers.getJSONObject(j);
            String email=speaker.getString("email");
            String name=speaker.getString("name");
            if (!speakerName.toString().isEmpty()) {
                speakerName.append(" and ");
            }

            speakerName.append(name);
            mail.addTo(email);
        }
        return speakerName.toString();
    }

    private String generateMessage(String title, String talkType, String speakerName, String submitLink, String confirmLocation) {
        String message;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("acceptanceTemplate.txt")) {
            String template = EmsCommunicator.toString(is);
            message = template
                    .replaceAll("#title#", title)
                    .replaceAll("#speakername#", speakerName)
                    .replaceAll("#talkType#", talkType)
                    .replaceAll("#submititLink#", submitLink)
                    .replaceAll("#confirmLink#", confirmLocation)
                    ;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return message;
    }

    private String talkTypeText(String format) {
        if ("presentation".equals(format)) {
            return "presentation";
        }
        if ("lightning-talk".equals(format)) {
            return "lightning talk";
        }
        if ("workshop".equals(format)) {
            return "workshop";
        }
        return "Unknown";
    }

}