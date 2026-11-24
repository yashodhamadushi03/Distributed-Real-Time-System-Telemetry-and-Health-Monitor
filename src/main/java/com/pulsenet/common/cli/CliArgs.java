package com.pulsenet.common.cli;

/**
 * Validating accessors for command-line option values. Every failure is reported as a
 * {@link UsageException} whose message can be shown to the operator as is.
 */
public final class CliArgs {

    private CliArgs() {
    }

    /** Signals a missing or invalid command-line option value. */
    public static final class UsageException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public UsageException(String message) {
            super(message);
        }
    }

    /**
     * Returns the text following the option at {@code optionIndex}.
     *
     * @param args        all command-line arguments
     * @param optionIndex index of the option itself
     * @return the option's value
     */
    public static String text(String[] args, int optionIndex) {
        if (optionIndex + 1 >= args.length || args[optionIndex + 1].isBlank()) {
            throw new UsageException(args[optionIndex] + " needs a value");
        }
        return args[optionIndex + 1].trim();
    }

    /**
     * Returns the integer following the option at {@code optionIndex}.
     *
     * @param min smallest accepted value
     * @param max largest accepted value
     */
    public static long number(String[] args, int optionIndex, long min, long max) {
        String text = text(args, optionIndex);
        try {
            long value = Long.parseLong(text);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new UsageException(String.format("%s must be a whole number from %d to %d, not '%s'",
                args[optionIndex], min, max, text));
    }

    /**
     * Returns the port number (1-65535) following the option at {@code optionIndex}.
     */
    public static int port(String[] args, int optionIndex) {
        return (int) number(args, optionIndex, 1, 65_535);
    }

    /**
     * Returns the decimal number following the option at {@code optionIndex}.
     *
     * @param min smallest accepted value
     * @param max largest accepted value
     */
    public static double decimal(String[] args, int optionIndex, double min, double max) {
        String text = text(args, optionIndex);
        try {
            double value = Double.parseDouble(text);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new UsageException(String.format("%s must be a number from %s to %s, not '%s'",
                args[optionIndex], min, max, text));
    }
}
