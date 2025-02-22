/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.request.body;

import java.util.List;

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.multipart.MultipartBody;
import org.asynchttpclient.multipart.MultipartBodyFactory;
import org.asynchttpclient.multipart.Part;

public class NettyMultipartBody implements NettyBody {

    private final MultipartBody multipartBody;

    public NettyMultipartBody(List<Part> parts, FluentCaseInsensitiveStringsMap headers) {
        multipartBody = MultipartBodyFactory.newMultipartBody(parts, headers);
    }

    public MultipartBody getMultipartBody() {
        return multipartBody;
    }

    @Override
    public long getContentLength() {
        return multipartBody.getContentLength();
    }

    @Override
    public String getContentType() {
        return multipartBody.getContentType();
    }
}
