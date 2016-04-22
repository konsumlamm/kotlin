/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.android.tests;

import junit.framework.TestCase;

import java.lang.reflect.Method;

public class AbstractCodegenTestCaseOnAndroid extends TestCase {

    protected void invokeBoxMethod(String filePath, String expectedResult) throws Exception {
        try {
            String simpelName = filePath.substring(filePath.lastIndexOf("/") + 1);
            String packageName = filePath.replaceAll("\\\\|-|\\.|/", "_");
            Class clazz = Class.forName(packageName + "." + getPackageClassName(simpelName));
            Method method = clazz.getMethod("box");
            assertEquals(expectedResult, method.invoke(null));
        }
        catch (Throwable e) {
            throw new RuntimeException("File: " + filePath, e);
        }
    }

    public static String getPackageClassName(String fileName) {
        return Character.toUpperCase(fileName.charAt(0)) + fileName.substring(1).replaceAll("\\.kt", "Kt");
    }
}
