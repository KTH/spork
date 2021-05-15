package se.kth.spork.spoon.printer;

import java.util.*;
import kotlin.Pair;
import se.kth.spork.spoon.conflict.StructuralConflict;
import se.kth.spork.spoon.pcsinterpreter.SpoonTreeBuilder;
import spoon.compiler.Environment;
import spoon.reflect.code.CtCatchVariable;
import spoon.reflect.code.CtComment;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.DefaultTokenWriter;
import spoon.reflect.visitor.PrinterHelper;
import spoon.reflect.visitor.PrintingContext;

public final class SporkPrettyPrinter extends DefaultJavaPrettyPrinter {
    public static final String START_CONFLICT = "<<<<<<< LEFT";
    public static final String MID_CONFLICT = "=======";
    public static final String END_CONFLICT = ">>>>>>> RIGHT";

    private static final Map<String, Pair<String, String>> DEFAULT_CONFLICT_MAP =
            Collections.emptyMap();

    private final SporkPrinterHelper printerHelper;
    private final String lineSeparator = getLineSeparator();

    private Map<String, Pair<String, String>> globalContentConflicts;

    private Deque<Optional<Map<String, Pair<String, String>>>> localContentConflictMaps;

    public SporkPrettyPrinter(Environment env) {
        super(env);
        printerHelper = new SporkPrinterHelper(env);
        localContentConflictMaps = new ArrayDeque<>();
        setPrinterTokenWriter(new DefaultTokenWriter(printerHelper));

        // This is required to avoid NullPointerExceptions when debugging, as the debugger calls
        // toString from time
        // to time
        localContentConflictMaps.push(Optional.empty());

        // this line is SUPER important because without it implicit elements will be printed. For
        // example,
        // instead of just String, it will print java.lang.String. Which isn't great.
        setIgnoreImplicit(false);

        // TODO detect these values instead of setting unconditionally
        printerHelper.setLineSeparator("\n");

        globalContentConflicts = DEFAULT_CONFLICT_MAP;
    }

    /** Check if the element is a multi declaration (i.e. something like `int a, b, c;`. */
    private static boolean isMultiDeclaration(CtElement e, String declarationSource) {
        if (!(e instanceof CtField || e instanceof CtLocalVariable || e instanceof CtCatchVariable))
            return false;

        boolean encounteredComma = false;
        for (char c : declarationSource.toCharArray()) {
            if (c == ',') {
                encounteredComma = true;
                break;
            } else if (c == ';') {
                break;
            }
        }
        return encounteredComma;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void enter(CtElement e) {
        localContentConflictMaps.push(
                Optional.ofNullable(
                        (Map<String, Pair<String, String>>)
                                e.getMetadata(PrinterPreprocessor.LOCAL_CONFLICT_MAP_KEY)));

        if (globalContentConflicts == DEFAULT_CONFLICT_MAP) {
            Map<String, Pair<String, String>> globals =
                    (Map<String, Pair<String, String>>)
                            e.getMetadata(PrinterPreprocessor.GLOBAL_CONFLICT_MAP_KEY);
            if (globals != null) {
                globalContentConflicts = globals;
            }
        }

        super.enter(e);
    }

    @Override
    protected void exit(CtElement e) {
        localContentConflictMaps.pop();
        super.exit(e);
    }

    @Override
    public SporkPrettyPrinter scan(CtElement e) {
        if (e == null) {
            return this;
        } else if (e.getMetadata(SpoonTreeBuilder.SINGLE_REVISION_KEY) != null
                && (e instanceof CtMethod || e instanceof CtField)
                && SourceExtractor.hasSourcePos(e)) {
            CtElement origNode = (CtElement) e.getMetadata(SpoonTreeBuilder.ORIGINAL_NODE_KEY);
            String originalSource = SourceExtractor.getOriginalSource(origNode);
            if (!isMultiDeclaration(e, originalSource)) {
                if (!(e instanceof CtMethod)) {
                    // inline comments are not included in the source code fragment
                    e.getComments().stream()
                            .filter(
                                    comment ->
                                            comment.getCommentType()
                                                    == CtComment.CommentType.INLINE)
                            .forEach(
                                    comment -> {
                                        String source = SourceExtractor.getOriginalSource(comment);
                                        int indent = SourceExtractor.getIndentation(comment);
                                        printerHelper.writeRawSourceCode(source, indent).writeln();
                                    });
                }

                printerHelper.writeRawSourceCode(
                        originalSource, SourceExtractor.getIndentation(origNode));
                return this;
            }
        }

        StructuralConflict structuralConflict =
                (StructuralConflict) e.getMetadata(StructuralConflict.METADATA_KEY);
        if (structuralConflict != null) {
            handleStructuralConflict(e, structuralConflict);
        } else {
            if (getContext().forceWildcardGenerics()) {
                // Forcing wildcard generics can cause crashes when references can't be resolved, so
                // we don't want
                // to do it. An example of where this sometimes causes crashes is if a nested class
                // is used in an
                // instanceof check.
                try (PrintingContext.Writable _context = getContext().modify()) {
                    _context.forceWildcardGenerics(false);
                    super.scan(e);
                }
            } else {
                super.scan(e);
            }
        }

        return this;
    }

    @Override
    public void visitCtComment(CtComment comment) {
        Object rawConflict = comment.getMetadata(PrinterPreprocessor.RAW_COMMENT_CONFLICT_KEY);
        if (rawConflict != null) {
            printerHelper.writeRawConflict(rawConflict.toString());
        } else if (comment.getMetadata(SpoonTreeBuilder.SINGLE_REVISION_KEY) != null) {
            String source = SourceExtractor.getOriginalSource(comment);
            int indent = SourceExtractor.getIndentation(comment);
            printerHelper.writeRawSourceCode(source, indent);
        } else {
            super.visitCtComment(comment);
        }
    }

    private void handleStructuralConflict(
            CtElement element, StructuralConflict structuralConflict) {
        String merge = structuralConflict.getLineBasedMerge();
        if (merge != null) {
            int indentation = SourceExtractor.getIndentation(element);
            printerHelper.writeRawSourceCode(merge, indentation);
        } else {
            writeStructuralConflict(structuralConflict);
        }
    }

    /** Write both pats of a structural conflict. */
    private void writeStructuralConflict(StructuralConflict structuralConflict) {
        String leftSource = SourceExtractor.getOriginalSource(structuralConflict.getLeft());
        String rightSource = SourceExtractor.getOriginalSource(structuralConflict.getRight());
        printerHelper.writeConflict(leftSource, rightSource);
    }

    private class SporkPrinterHelper extends PrinterHelper {
        public SporkPrinterHelper(Environment env) {
            super(env);
        }

        @Override
        public SporkPrinterHelper write(String s) {
            Optional<Map<String, Pair<String, String>>> localConflictMap =
                    localContentConflictMaps.peek();
            String trimmed = s.trim();
            if (trimmed.startsWith(START_CONFLICT)
                    || trimmed.startsWith(MID_CONFLICT)
                    || trimmed.startsWith(END_CONFLICT)) {
                // All we need to do here is the decrease tabs and enter some appropriate whitespace
                writelnIfNotPresent().writeAtLeftMargin(s);
                return this;
            }

            String strippedQuotes = trimmed.replaceAll("\"", "");
            if (globalContentConflicts.containsKey(strippedQuotes)) {
                Pair<String, String> conflict = globalContentConflicts.get(strippedQuotes);
                writeConflict(conflict.getFirst(), conflict.getSecond());
            } else if (localConflictMap.isPresent() && localConflictMap.get().containsKey(s)) {
                Pair<String, String> conflict = localConflictMap.get().get(s);
                writeConflict(conflict.getFirst(), conflict.getSecond());
            } else {
                super.write(s);
            }

            return this;
        }

        public SporkPrinterHelper writeConflict(String left, String right) {
            writelnIfNotPresent()
                    .writeAtLeftMargin(START_CONFLICT)
                    .writeln()
                    .writeAtLeftMargin(left)
                    .writelnIfNotPresent()
                    .writeAtLeftMargin(MID_CONFLICT)
                    .writeln()
                    .writeAtLeftMargin(right)
                    .writelnIfNotPresent()
                    .writeAtLeftMargin(END_CONFLICT)
                    .writeln();
            return this;
        }

        @Override
        public SporkPrinterHelper writeln() {
            super.writeln();
            return this;
        }

        /**
         * Write a line separator only if the last written string did not end with a line separator.
         */
        private SporkPrinterHelper writelnIfNotPresent() {
            String lastWritten = sbf.length() > 0 ? sbf.substring(sbf.length() - 1) : "";
            if (lastWritten.equals(lineSeparator)) return this;
            return writeln();
        }

        private SporkPrinterHelper writeAtLeftMargin(String s) {
            if (s.isEmpty()) return this;

            int tabBefore = getTabCount();
            setTabCount(0);
            super.write(s);
            setTabCount(tabBefore);
            return this;
        }

        /** Write raw source code, attempting to honor indentation. */
        public SporkPrinterHelper writeRawSourceCode(String s, int indentationCount) {
            String[] lines = s.split("\n");

            int initialTabCount = getTabCount();
            setTabCount(indentationCount / env.getTabulationSize());

            for (int i = 0; i < lines.length; i++) {
                if (i != 0) {
                    writeln();
                }
                String line = lines[i];
                write(trimIndentation(line, indentationCount));
            }
            setTabCount(initialTabCount);
            return this;
        }

        private String trimIndentation(String s, int trimAmount) {
            if (s.length() >= trimAmount && isOnlyWhitespace(s.substring(0, trimAmount))) {
                return s.substring(trimAmount);
            }
            return s;
        }

        private boolean isOnlyWhitespace(String s) {
            for (char c : s.toCharArray()) {
                if (!Character.isWhitespace(c)) return false;
            }
            return true;
        }

        /** Write a raw conflict. Typically, this is used for writing out conflicts in comments. */
        public void writeRawConflict(String s) {
            // When getting raw comments from Spoon, they don't include leading whitespace for the
            // first line,
            // so we check if that's needed
            if (getTabCount() > 0 && !s.startsWith("\\s")) {
                // not indented, so we write the first line normally
                String[] lines = s.split(lineSeparator, 2);
                write(lines[0]).writelnIfNotPresent();

                if (lines.length > 1) {
                    // we write the rest of the content with 0 indentation
                    writeAtLeftMargin(lines[1]);
                }
            } else {
                writeAtLeftMargin(s);
            }
        }
    }
}
