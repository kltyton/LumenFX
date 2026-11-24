package com.kltyton.lumenfx.effect.niagara.parameter;

/** Component User parameters accept the full name and its redirected display name. */
public final class UserParameterNames {
    private UserParameterNames() { }
    public static String display(String name) {
        return name.length() > 5 && SourceNames.equal(name.substring(0, 5), "User.") ? name.substring(5) : name;
    }
    public static boolean equal(String left, String right) { return SourceNames.equal(display(left), display(right)); }
}
