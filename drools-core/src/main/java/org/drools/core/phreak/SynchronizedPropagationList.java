/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.drools.core.phreak;

import java.util.Iterator;

import org.drools.core.common.ReteEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SynchronizedPropagationList implements PropagationList {

    protected static final Logger log = LoggerFactory.getLogger( SynchronizedPropagationList.class );

    protected final ReteEvaluator reteEvaluator;

    protected volatile PropagationEntry head;
    protected volatile PropagationEntry tail;

    private volatile boolean disposed = false;

    private volatile boolean hasEntriesDeferringExpiration = false;

    private volatile boolean firingUntilHalt = false;

    public SynchronizedPropagationList(ReteEvaluator reteEvaluator) {
        this.reteEvaluator = reteEvaluator;
    }

    @Override
    public void addEntry(final PropagationEntry entry) {
        if (entry.requiresImmediateFlushing()) {
        	if (entry.isCalledFromRHS()) {
        		entry.execute(reteEvaluator);
        	} else {
        		reteEvaluator.getActivationsManager().executeTask( new ExecutableEntry() {
        			@Override
                    public void execute() {
                        if (entry instanceof PhreakTimerNode.TimerAction) {
                            ( (PhreakTimerNode.TimerAction) entry ).execute( reteEvaluator, true );
                        } else {
                            entry.execute( reteEvaluator );
                        }
                    }

                    @Override
                    public void enqueue() {
                        internalAddEntry( entry );
                    }
                } );
        	}
        } else {
            internalAddEntry( entry );
        }
    }

    synchronized void internalAddEntry( PropagationEntry entry ) {
        if ( head == null ) {
            head = entry;
            if (firingUntilHalt) {
                notifyWaitOnRest();
            }
        } else {
            tail.setNext( entry );
        }
        tail = entry;
        hasEntriesDeferringExpiration |= entry.defersExpiration();
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    @Override
    public void flush() {
        flush( reteEvaluator, takeAll() );
    }

    @Override
    public void flush(PropagationEntry currentHead) {
        flush( reteEvaluator, currentHead );
    }

    private void flush( ReteEvaluator reteEvaluator, PropagationEntry currentHead ) {
        for (PropagationEntry entry = currentHead; !disposed && entry != null; entry = entry.getNext()) {
            entry.execute(reteEvaluator);
        }
    }

    public boolean hasEntriesDeferringExpiration() {
        return hasEntriesDeferringExpiration;
    }

    @Override
    public synchronized PropagationEntry takeAll() {
        PropagationEntry currentHead = head;
        head = null;
        tail = null;
        hasEntriesDeferringExpiration = false;
        return currentHead;
    }

    @Override
    public synchronized void reset() {
        head = null;
        tail = null;
        disposed = false;
    }

    @Override
    public synchronized boolean isEmpty() {
        return head == null;
    }

    public synchronized void waitOnRest() {
        try {
            wait();
        } catch (InterruptedException e) {
            // do nothing
        }
    }


    @Override
    public synchronized void notifyWaitOnRest() {
        notifyAll();
    }

    @Override
    public synchronized Iterator<PropagationEntry> iterator() {
        return new PropagationEntryIterator(head);
    }

    public static class PropagationEntryIterator implements Iterator<PropagationEntry> {

        private PropagationEntry next;

        public PropagationEntryIterator(PropagationEntry head) {
            this.next = head;
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public PropagationEntry next() {
            PropagationEntry current = next;
            next = current.getNext();
            return current;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public void onEngineInactive() { }

    public void setFiringUntilHalt( boolean firingUntilHalt ) {
        this.firingUntilHalt = firingUntilHalt;
    }
}
