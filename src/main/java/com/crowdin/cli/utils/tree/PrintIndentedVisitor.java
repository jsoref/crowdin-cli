package com.crowdin.cli.utils.tree;

import com.crowdin.cli.BaseCli;
import com.crowdin.cli.utils.tree.interfaces.Visitor;


public class PrintIndentedVisitor implements Visitor<String, String> {

    private static final String ELEM = BaseCli.RESOURCE_BUNDLE.getString("message.tree.elem");
    private static final String LAST_ELEM = BaseCli.RESOURCE_BUNDLE.getString("message.tree.last_elem");
    private static final String DIR = BaseCli.RESOURCE_BUNDLE.getString("message.tree.dir");
    private static final String LAST_DIR = BaseCli.RESOURCE_BUNDLE.getString("message.tree.last_dir");

    private final String indent;
    private final boolean last;

    public PrintIndentedVisitor() {
        this.indent = "";
        this.last = true;
    }

    private PrintIndentedVisitor(String indent, boolean last) {
        this.indent = indent;
        this.last = last;
    }

    @Override
    public Visitor<String, String> visitTree(Tree<String> tree, boolean last) {
        String newIndent = indent + (this.last ? LAST_DIR : DIR);
        return new PrintIndentedVisitor(newIndent, last);
    }

    @Override
    public String visitData(Tree<String> parent, String data) {
        return indent + (last ? LAST_ELEM : ELEM) + data;
    }
}
