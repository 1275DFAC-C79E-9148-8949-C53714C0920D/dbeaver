/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.editors.sql.syntax;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

import java.util.*;

/**
 * SQLReconcilingStrategy
 */
public class SQLReconcilingStrategy implements IReconcilingStrategy, IReconcilingStrategyExtension {
    private static final Log log = Log.getLog(SQLReconcilingStrategy.class);

    private static final Comparator<SQLScriptPosition> COMPARATOR = Comparator.comparingInt(SQLScriptPosition::getOffset).thenComparingInt(SQLScriptPosition::getLength);

    private final NavigableSet<SQLScriptPosition> registeredPositions = new TreeSet<>(COMPARATOR);

    private SQLEditorBase editor;

    private IDocument document;

    private IProgressMonitor monitor; //TODO use me

    public SQLEditorBase getEditor() {
        return editor;
    }

    public void setEditor(SQLEditorBase editor) {
        this.editor = editor;
    }

    @Override
    public void setDocument(IDocument document) {
        this.document = document;
    }

    @Override
    public void reconcile(DirtyRegion dirtyRegion, IRegion subRegion) {
        int length = 0;
        if (DirtyRegion.INSERT.equals(dirtyRegion.getType())) {
            length = subRegion.getLength();
        }
        reconcile(subRegion.getOffset(), length);
    }

    @Override
    public void reconcile(IRegion partition) {
        reconcile(partition.getOffset(), partition.getLength());
    }

    @Override
    public void setProgressMonitor(IProgressMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void initialReconcile() {
        reconcile(0, document.getLength());
    }

    private void reconcile(int damagedRegionOffset, int damagedRegionLength) {
        if (!editor.isFoldingEnabled()) {
            return;
        }
        ProjectionAnnotationModel model = editor.getAnnotationModel();
        if (model == null) {
            log.debug("Attempt to change folding annotations on editor with empty annotation model. editor=" + editor);
            return;
        }
        SQLScriptPosition damagedRegionPosition = new SQLScriptPosition(damagedRegionOffset, damagedRegionLength, false, null);
        SQLScriptPosition positionToTheLeft = positionToTheLeft(damagedRegionPosition);
        SQLScriptPosition positionToTheRight = positionToTheRight(damagedRegionPosition);
        int investigationRegionOffset = getInvestigationRegionOffset(positionToTheLeft);
        int investigationRegionLength = getInvestigationRegionLength(positionToTheRight, investigationRegionOffset);
        NavigableSet<SQLScriptPosition> investigationSet = getInvestigationSet(positionToTheLeft, positionToTheRight);
        Iterable<SQLScriptElement> queries = getQueries(investigationRegionOffset, investigationRegionLength);
        Map<Annotation, Position> newAnnotations = new HashMap<>();
        Collection<SQLScriptPosition> newRegisteredPositions = new ArrayList<>();
        for (SQLScriptElement element: queries) {
            if (deservesFolding(element)) {
                SQLScriptPosition position = retrievePosition(element, investigationSet);
                newRegisteredPositions.add(position);
                newAnnotations.put(position.getFoldingAnnotation(), position);
            }
        }
        Annotation[] oldAnnotations = collectAnnotations(investigationSet);
        model.modifyAnnotations(oldAnnotations, newAnnotations, null);
        registeredPositions.addAll(newRegisteredPositions);
    }

    private static int getInvestigationRegionOffset(@Nullable SQLScriptPosition positionToTheLeft) {
        if (positionToTheLeft != null) {
            return positionToTheLeft.getOffset() + positionToTheLeft.getLength();
        }
        return 0;
    }

    private int getInvestigationRegionLength(@Nullable SQLScriptPosition positionToTheRight, int investigationRegionOffset) {
        if (positionToTheRight != null && getQueries(positionToTheRight.getOffset(), positionToTheRight.getLength()).size() == 1) {
            return positionToTheRight.getOffset() - investigationRegionOffset;
        }
        return document.getLength();
    }

    @Nullable
    private SQLScriptPosition positionToTheLeft(SQLScriptPosition position) {
        NavigableSet<SQLScriptPosition> set = registeredPositions.headSet(position, false).descendingSet();
        for (SQLScriptPosition positionToTheLeft: set) {
            if (positionToTheLeft.getOffset() + positionToTheLeft.getLength() <= position.getOffset()) {
                return positionToTheLeft;
            }
        }
        return null;
    }

    @Nullable
    private SQLScriptPosition positionToTheRight(SQLScriptPosition position) {
        SortedSet<SQLScriptPosition> set = registeredPositions.tailSet(position);
        for (SQLScriptPosition positionToTheRight: set) {
            if (position.getOffset() + position.getLength() <= positionToTheRight.getOffset()) {
                return positionToTheRight;
            }
        }
        return null;
    }

    private NavigableSet<SQLScriptPosition> getInvestigationSet(@Nullable SQLScriptPosition positionToTheLeft,
                                                                @Nullable SQLScriptPosition positionToTheRight) {
        if (positionToTheLeft == null && positionToTheRight == null) {
            return registeredPositions;
        }
        if (positionToTheLeft == null) {
            return registeredPositions.headSet(positionToTheRight, false);
        }
        if (positionToTheRight == null) {
            return registeredPositions.tailSet(positionToTheLeft, false);
        }
        return registeredPositions.subSet(positionToTheLeft, false, positionToTheRight, false);
    }

    private boolean deservesFolding(SQLScriptElement element) {
        int numberOfLines = getNumberOfLines(element);
        if (numberOfLines == 1) {
            return false;
        }
        if (element.getOffset() + element.getLength() != document.getLength() && expandQueryLength(element) == element.getLength()) {
            return numberOfLines > 2;
        }
        return true;
    }

    private int getNumberOfLines(SQLScriptElement element) {
        try {
            return document.getLineOfOffset(element.getOffset() + element.getLength()) - document.getLineOfOffset(element.getOffset()) + 1;
        } catch (BadLocationException e) {
            throw new SQLReconcilingStrategyException(e);
        }
    }

    //expands query to the end of the line if there are only whitespaces after it. Returns desired length.
    private int expandQueryLength(SQLScriptElement element) {
        int position = element.getOffset() + element.getLength();
        while (position < document.getLength()) {
            char c = unsafeGetChar(position);
            if (c == '\n') { //fixme really '\n'?
                if (position + 1 < document.getLength()) {
                    position++;
                    break;
                }
            }
            if (Character.isWhitespace(c)) {
                position++;
            } else {
                return element.getLength();
            }
        }
        return position - element.getOffset();
    }

    private char unsafeGetChar(int index) {
        try {
            return document.getChar(index);
        } catch (BadLocationException e) {
            throw new SQLReconcilingStrategyException(e);
        }
    }

    private Collection<SQLScriptElement> getQueries(int offset, int length) {
        List<SQLScriptElement> queries = unsafeGetQueries(offset, length);
        if (queries == null) {
            editor.reloadParserContext();
        }
        return unsafeGetQueries(offset, length);
    }

    @Nullable
    private List<SQLScriptElement> unsafeGetQueries(int offset, int length) {
        return editor.extractScriptQueries(offset, length, false, true, false);
    }

    private static class SQLReconcilingStrategyException extends RuntimeException {
        private SQLReconcilingStrategyException(Throwable cause) {
            super(cause);
        }
    }

    private SQLScriptPosition retrievePosition(SQLScriptElement element, NavigableSet<SQLScriptPosition> investigationSet) {
        int expandedQueryLength = expandQueryLength(element);
        SQLScriptPosition position = new SQLScriptPosition(element.getOffset(), expandedQueryLength, true, new ProjectionAnnotation());
        SQLScriptPosition positionInSet = get(investigationSet, position);
        if (positionInSet == null) {
            return position;
        }
        return positionInSet;
    }

    @Nullable
    private static SQLScriptPosition get(SortedSet<SQLScriptPosition> set, SQLScriptPosition position) {
        for (SQLScriptPosition p: set) {
            int comp = COMPARATOR.compare(position, p);
            if (comp == 0) {
                return position;
            }
            if (comp > 0) {
                break;
            }
        }
        return null;
    }

    private static Annotation[] collectAnnotations(Collection<SQLScriptPosition> collection) {
        Annotation[] array = new Annotation[collection.size()];
        int i = 0;
        for (SQLScriptPosition position: collection) {
            array[i] = position.getFoldingAnnotation();
            i++;
        }
        return array;
    }
}
