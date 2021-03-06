package no.javazone.cake.redux;

import no.javazone.cake.redux.mail.MailSenderImplementation;
import no.javazone.cake.redux.mail.MailSenderService;
import no.javazone.cake.redux.mail.SmtpMailSender;
import no.javazone.cake.redux.sleepingpill.SleepingpillCommunicator;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.jsonbuddy.JsonArray;
import org.jsonbuddy.JsonFactory;
import org.jsonbuddy.JsonObject;
import org.jsonbuddy.parse.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AcceptorSetter {
    //private EmsCommunicator emsCommunicator;
    private SleepingpillCommunicator sleepingpillCommunicator;
    private UserFeedbackCommunicator userFeedbackCommunicator;

    public AcceptorSetter(SleepingpillCommunicator sleepingpillCommunicator,
                          UserFeedbackCommunicator userFeedbackCommunicator) {
        this.sleepingpillCommunicator = sleepingpillCommunicator;
        this.userFeedbackCommunicator = userFeedbackCommunicator;
    }

    public String accept(JsonArray talks,UserAccessType userAccessType) {
        String template = loadTemplate();
        String tagToAdd = "accepted";
        String tagExistsErrormessage = "Talk is already accepted";
        String subjectTemplate = "Trondheim Developer Conference 2019 #talkType# accepted";

        return doUpdates(talks, template, subjectTemplate, tagToAdd, tagExistsErrormessage,userAccessType,false);
    }

    public String massUpdate(JsonObject jsonObject,UserAccessType userAccessType) {
        JsonArray talks = jsonObject.requiredArray("talks");

        String template = null;
        String subjectTemplate = null;
        if ("true".equals(jsonObject.requiredString("doSendMail"))) {
            template = jsonObject.requiredString("message");
            subjectTemplate = jsonObject.requiredString("subject");
        };

        String tagToAdd = null;
        if ("true".equals(jsonObject.requiredString("doTag"))) {
            tagToAdd = jsonObject.requiredString("newtag");
        }
        String tagExistsErrormessage = "Tag already exsists";

        boolean publishUpdates = "true".equals(jsonObject.stringValue("publishUpdates").orElse("false"));

        return doUpdates(talks, template, subjectTemplate, tagToAdd, tagExistsErrormessage,userAccessType,publishUpdates);
    }

    private String doUpdates(JsonArray talks, String template, String subjectTemplate, String tagToAdd, String tagExistsErrormessage,UserAccessType userAccessType,boolean publishUpdates) {
        JsonArray statusAllTalks = JsonFactory.jsonArray();
        for (int i=0;i<talks.size();i++) {
            JsonObject accept = JsonFactory.jsonObject();
            statusAllTalks.add(accept);
            try {
                String encodedTalkRef = talks.get(i,JsonObject.class).requiredString("ref");
                JsonObject jsonTalk = sleepingpillCommunicator.oneTalkStripped(encodedTalkRef);

                String conferenceId = jsonTalk.requiredString("conferenceId");
                Optional<String> feedback = userFeedbackCommunicator.feedback(conferenceId, encodedTalkRef);
                if (feedback.isPresent()) {
                    JsonObject jsonFeedback = JsonParser.parseToObject(feedback.get());
                    JsonObject paperfeedback = jsonFeedback.requiredObject("session").requiredObject("paper");
                    long green = paperfeedback.requiredLong("green");
                    long red = paperfeedback.requiredLong("red");
                    System.out.println("Green " + green + " red " + red);
                    jsonTalk.put("papergreen", green);
                    jsonTalk.put("paperred", red);
                } else {
                    System.out.println("No feedback");
                    return "";
                }
                accept.put("title",jsonTalk.requiredString("title"));

                List<String> tags = jsonTalk.requiredArray("tags").strings();

                if (tagToAdd != null && tags.contains(tagToAdd)) {
                    accept.put("status","error");
                    accept.put("message", tagExistsErrormessage);
                    continue;
                }

                if (template != null) {
                    generateAndSendMail(template, subjectTemplate, encodedTalkRef, jsonTalk);
                }

                if (tagToAdd != null) {
                    tags.add(tagToAdd);
                    String lastModified = jsonTalk.requiredString("lastModified");
                    sleepingpillCommunicator.updateTags(encodedTalkRef, tags, userAccessType,lastModified);
                }
                if (publishUpdates) {
                    sleepingpillCommunicator.pubishChanges(encodedTalkRef,userAccessType);
                }
                accept.put("status","ok");
                accept.put("message","ok");

            } catch (EmailException e) {
                    accept.put("status","error");
                    accept.put("message","Error: " + e.getMessage());
            }
        }
        return statusAllTalks.toJson();
    }

    private void generateAndSendMail(
            String template,
            String subjectTemplate,
            String encodedTalkRef,
            JsonObject jsonTalk) throws EmailException {
        String talkType = talkTypeText(jsonTalk.requiredString("format"));
        String submitLink = Configuration.submititLocation() + encodedTalkRef;
        String confirmLocation = Configuration.cakeLocation() + "confirm.html?id=" + encodedTalkRef;
        String title = jsonTalk.requiredString("title");

        SimpleEmail mail = new SimpleEmail();
        String speakerName = addSpeakers(jsonTalk, mail);

        String subject = generateMessage(subjectTemplate, title, talkType, speakerName, submitLink, confirmLocation,jsonTalk);
        setupMailHeader(mail,subject);

        String message = generateMessage(template,title, talkType, speakerName, submitLink, confirmLocation,jsonTalk);
        mail.setMsg(message);
        MailSenderService.get().sendMail(MailSenderImplementation.create(mail));
    }




    public static SimpleEmail setupMailHeader(SimpleEmail mail,String subject) throws EmailException {
        mail.setHostName(Configuration.smtpServer());
        mail.setFrom(Configuration.mailFrom(), Configuration.mailFromName());
        mail.addBcc(Configuration.mailFrom());
        mail.setSubject(subject);


        if (Configuration.useMailSSL()) {
            mail.setSSLOnConnect(true);
            mail.setSslSmtpPort("" + Configuration.smtpPort());
        } else {
            mail.setSmtpPort(Configuration.smtpPort());

        }
        String mailUser = Configuration.mailUser();
        if (mailUser != null) {
            mail.setAuthentication(mailUser, Configuration.mailPassword());
        }

        return mail;
    }

    private String addSpeakers(JsonObject jsonTalk, SimpleEmail mail) throws EmailException {
        JsonArray jsonSpeakers = jsonTalk.requiredArray("speakers");
        StringBuilder speakerName=new StringBuilder();
        for (int j=0;j<jsonSpeakers.size();j++) {
            JsonObject speaker = jsonSpeakers.get(j,JsonObject.class);
            String email=speaker.requiredString("email");
            String name=speaker.requiredString("name");
            if (!speakerName.toString().isEmpty()) {
                speakerName.append(" and ");
            }

            speakerName.append(name);
            mail.addTo(email);
        }
        return speakerName.toString();
    }

    private String replaceAll(String s, String code,String replacement) {
        StringBuilder builder = new StringBuilder(s);
        for (int ind = builder.indexOf(code);ind!=-1;ind = builder.indexOf(code)) {
            builder.replace(ind,ind+code.length(),replacement);
        }
        return builder.toString();
    }

    protected String generateMessage(String template, String title, String talkType, String speakerName, String submitLink, String confirmLocation,JsonObject jsonTalk) {
        String message = template;
        message = replaceAll(message,"#title#", title);
        message = replaceAll(message,"#speakername#", speakerName);
        message = replaceAll(message,"#talkType#", talkType);
        message = replaceAll(message,"#submititLink#", submitLink);
        message = replaceAll(message,"#confirmLink#", confirmLocation);

        Optional<Long> paperred = jsonTalk.longValue("paperred");
        Optional<Long> papergreen = jsonTalk.longValue("papergreen");
        System.out.println(paperred + " red");
        System.out.println(papergreen + " green");
        if(papergreen.isPresent() && paperred.isPresent()) {
            long mehCount = paperred.get();
            long awesomeCount = papergreen.get();
            double total = mehCount + awesomeCount;
            long mehRatio = (long)((mehCount / total) * 100);
            long awesomeRatio = 100 - mehRatio;
            System.out.println(mehRatio + " red");
            System.out.println(awesomeRatio + " green");
            message = replaceAll(message,"#paperred#", String.valueOf(mehRatio));
            message = replaceAll(message,"#papergreen#", String.valueOf(awesomeRatio));
        }

        for (int pos=message.indexOf("#");pos!=-1;pos=message.indexOf("#",pos+1)) {
            if (pos == message.length()-1) {
                break;
            }
            int endpos = message.indexOf("#",pos+1);
            if (endpos == -1) {
                break;
            }
            String key = message.substring(pos+1,endpos);
            Optional<String> stringValue;
            switch (key) {
                case "slot":
                    stringValue = readSlot(jsonTalk);
                    break;
                case "room":
                    stringValue = readRoom(jsonTalk);
                    break;
                default:
                    stringValue = jsonTalk.stringValue(key);
            }
            if (!stringValue.isPresent()) {
                continue;
            }
            String before = message.substring(0,pos);
            String after = (endpos == message.length()-1) ? "" : message.substring(endpos+1);
            message =  before + stringValue.get() + after;
        }
        return message;
    }

    private Optional<String> readRoom(JsonObject jsonTalk) {
        String roomval = jsonTalk.objectValue("room")
                .map(ob -> ob.stringValue("name"))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .orElse("No room allocated");
        return Optional.of(roomval);
    }

    private Optional<String> readSlot(JsonObject jsonTalk) {
        Optional<String> startVal = jsonTalk.objectValue("slot")
                .map(ob -> ob.stringValue("start"))
                .filter(Optional::isPresent)
                .map(Optional::get);
        if (!startVal.isPresent()) {
            return Optional.of("No slot allocated");
        }
        LocalDateTime parse = LocalDateTime.parse(startVal.get());
        String val = parse.format(DateTimeFormatter.ofPattern("MMMM d 'at' HH:mm"));
        return Optional.of(val);
    }

    private String loadTemplate() {
        String template;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("acceptanceTemplate.txt")) {
            template = CommunicatorHelper.toString(is);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return template;
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
