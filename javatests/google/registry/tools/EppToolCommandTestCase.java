// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.flows.EppServletUtils.APPLICATION_EPP_XML_UTF8;
import static com.google.domain.registry.testing.DatastoreHelper.createTlds;
import static com.google.domain.registry.util.ResourceUtils.readResourceUtf8;
import static com.google.domain.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import com.google.domain.registry.testing.InjectRule;
import com.google.domain.registry.tools.ServerSideCommand.Connection;

import org.junit.Before;
import org.junit.Rule;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.List;

/**
 * Abstract class for commands that construct + send EPP commands.
 *
 * @param <C> the command type
 */
public abstract class EppToolCommandTestCase<C extends EppToolCommand> extends CommandTestCase<C> {

  @Rule
  public InjectRule inject = new InjectRule();

  @Mock
  Connection connection;

  @Captor
  ArgumentCaptor<byte[]> xml;

  @Before
  public void init() throws Exception {
    // Create two TLDs for commands that allow multiple TLDs at once.
    createTlds("tld", "tld2");
    command.setConnection(connection);
    initEppToolCommandTestCase();
  }

  /** Subclasses can override this to perform additional initialization. */
  void initEppToolCommandTestCase() throws Exception {}

  void verifySent(String fileToMatch, boolean dryRun, boolean superuser) throws Exception {
    ImmutableMap<String, ?> params = ImmutableMap.of(
        "clientIdentifier", "NewRegistrar",
        "superuser", superuser,
        "dryRun", dryRun);
    verify(connection)
        .send(eq("/_dr/epptool"), eq(params), eq(APPLICATION_EPP_XML_UTF8), xml.capture());
    assertXmlEquals(readResourceUtf8(getClass(), fileToMatch), new String(xml.getValue(), UTF_8));
  }

  void verifySent(List<String> filesToMatch, boolean dryRun, boolean superuser) throws Exception {
    ImmutableMap<String, ?> params = ImmutableMap.of(
        "clientIdentifier", "NewRegistrar",
        "superuser", superuser,
        "dryRun", dryRun);
    verify(connection, times(filesToMatch.size()))
        .send(eq("/_dr/epptool"), eq(params), eq(APPLICATION_EPP_XML_UTF8), xml.capture());
    List<byte[]> capturedXml = xml.getAllValues();
    assertThat(filesToMatch).hasSize(capturedXml.size());
    for (String fileToMatch : filesToMatch) {
      assertXmlEquals(
          readResourceUtf8(getClass(), fileToMatch),
          new String(capturedXml.get(filesToMatch.indexOf(fileToMatch)), UTF_8));
    }
  }
}