package com.infomedia.abacox.telephonypricing.constants;


import java.util.regex.Pattern;

public class Regexp {
    public static final String USERNAME = "^[A-Za-z0-9]+(?:[_-][A-Za-z0-9]+)*$";
    public static final String ROLENAME = "^[a-z0-9][a-z0-9-]*(?:-[a-z0-9]+)*$";
    public static final String PASSWORD = "^(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?])(?=.*\\d)(?=.*[A-Z])(?=.*[a-z]).*$";
    public static final String EMAIL = "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*" +
            "@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?";
    public static final String PHONE = "^\\+?\\d{5,20}$";
    public static final String IP = "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$|^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$";


    private Regexp() {
        Pattern.compile(USERNAME);
        Pattern.compile(ROLENAME);
        Pattern.compile(PASSWORD);
        Pattern.compile(EMAIL);
        Pattern.compile(PHONE);
        Pattern.compile(IP);
    }

    public static final String MSG_USERNAME = "Username must contain only letters, numbers, underscores and hyphens";
    public static final String MSG_ROLENAME = "Rolename must contain only lowercase letters, numbers and hyphens";
    public static final String MSG_PASSWORD = "Password must contain at least one uppercase letter, one lowercase letter, one digit and one special character";
    public static final String MSG_EMAIL = "Invalid email address";
    public static final String MSG_PHONE = "Invalid phone number";
    public static final String MSG_IP = "Invalid IP address";

}
