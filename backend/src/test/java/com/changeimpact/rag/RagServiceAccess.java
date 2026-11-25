package com.changeimpact.rag;

/** Test access to package-private helpers of {@link RagService}. */
public final class RagServiceAccess {
    private RagServiceAccess() {}

    public static String tsQuery(String q) {
        return RagService.tsQuery(q);
    }

    public static boolean codeLike(String q) {
        return RagService.CODE_LIKE.matcher(q).find();
    }
}
