/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2023 DBeaver Corp and others
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
 *
 * Contributors:
 *    Stefan Uhrig - initial implementation
 */
package org.jkiss.dbeaver.ext.hana.model.data.wkb;

/**
 * The dimension modes supported by HANA.
 */
public enum XyzmMode {
    XY(2, false, false), XYZ(3, true, false), XYM(3, false, true), XYZM(4, true, true);

    private int coordinatesPerPoint;

    private boolean hasZ;

    private boolean hasM;

    XyzmMode(int coordinatesPerPoint, boolean hasZ, boolean hasM) {
        this.coordinatesPerPoint = coordinatesPerPoint;
        this.hasZ = hasZ;
        this.hasM = hasM;
    }

    public int getCoordinatesPerPoint() {
        return coordinatesPerPoint;
    }

    public boolean hasZ() {
        return hasZ;
    }

    public boolean hasM() {
        return hasM;
    }
}
