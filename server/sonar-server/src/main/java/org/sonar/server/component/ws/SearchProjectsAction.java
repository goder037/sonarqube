/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.component.ws;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.Change;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.api.utils.DateUtils;
import org.sonar.core.util.stream.Collectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.property.PropertyDto;
import org.sonar.db.property.PropertyQuery;
import org.sonar.server.component.ws.FilterParser.Criterion;
import org.sonar.server.es.Facets;
import org.sonar.server.es.SearchIdResult;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.measure.index.ProjectMeasuresIndex;
import org.sonar.server.measure.index.ProjectMeasuresQuery;
import org.sonar.server.user.UserSession;
import org.sonarqube.ws.Common;
import org.sonarqube.ws.WsComponents.Component;
import org.sonarqube.ws.WsComponents.SearchProjectsWsResponse;
import org.sonarqube.ws.client.component.SearchProjectsRequest;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static org.sonar.api.measures.CoreMetrics.ALERT_STATUS_KEY;
import static org.sonar.api.measures.CoreMetrics.NCLOC_KEY;
import static org.sonar.api.server.ws.WebService.Param.FIELDS;
import static org.sonar.core.util.stream.Collectors.toSet;
import static org.sonar.server.component.ws.ProjectMeasuresQueryFactory.IS_FAVORITE_CRITERION;
import static org.sonar.server.component.ws.ProjectMeasuresQueryFactory.newProjectMeasuresQuery;
import static org.sonar.server.measure.index.ProjectMeasuresIndex.SUPPORTED_FACETS;
import static org.sonar.server.measure.index.ProjectMeasuresQuery.SORT_BY_NAME;
import static org.sonar.server.ws.WsUtils.checkFoundWithOptional;
import static org.sonar.server.ws.WsUtils.writeProtobuf;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_FILTER;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_ORGANIZATION;
import static org.sonarqube.ws.client.component.SearchProjectsRequest.DEFAULT_PAGE_SIZE;
import static org.sonarqube.ws.client.component.SearchProjectsRequest.MAX_PAGE_SIZE;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.FILTER_LANGUAGES;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.FILTER_TAGS;

public class SearchProjectsAction implements ComponentsWsAction {

  private static final String ANALYSIS_DATE = "analysisDate";
  private static final Set<String> POSSIBLE_FIELDS = newHashSet(ANALYSIS_DATE);

  private final DbClient dbClient;
  private final ProjectMeasuresIndex index;
  private final ProjectMeasuresQueryValidator queryValidator;
  private final UserSession userSession;

  public SearchProjectsAction(DbClient dbClient, ProjectMeasuresIndex index, ProjectMeasuresQueryValidator queryValidator, UserSession userSession) {
    this.dbClient = dbClient;
    this.index = index;
    this.queryValidator = queryValidator;
    this.userSession = userSession;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction("search_projects")
      .setSince("6.2")
      .setDescription("Search for projects")
      .addPagingParams(DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE)
      .setInternal(true)
      .setResponseExample(getClass().getResource("search_projects-example.json"))
      .setChangelog(new Change("6.4", format("The '%s' parameter accepts '%s' to filter by language", FILTER_LANGUAGES, PARAM_FILTER)))
      .setHandler(this);

    action.createFieldsParam(POSSIBLE_FIELDS)
      .setSince("6.4");
    action.createParam(PARAM_ORGANIZATION)
      .setDescription("the organization to search projects in")
      .setRequired(false)
      .setSince("6.3");
    action.createParam(Param.FACETS)
      .setDescription("Comma-separated list of the facets to be computed. No facet is computed by default.")
      .setPossibleValues(SUPPORTED_FACETS);
    action
      .createParam(PARAM_FILTER)
      .setDescription("Filter of projects on name, key, measure value, quality gate, language, tag or whether a project is a favorite or not.<br>" +
        "The filter must be encoded to form a valid URL (for example '=' must be replaced by '%3D').<br>" +
        "Examples of use:" +
        "<ul>" +
        " <li>to filter my favorite projects with a failed quality gate and a coverage greater than or equals to 60% and a coverage strictly lower than 80%:<br>" +
        "   <code>filter=\"alert_status = ERROR and isFavorite and coverage >= 60 and coverage < 80\"</code></li>" +
        " <li>to filter projects with a reliability, security and maintainability rating equals or worse than B:<br>" +
        "   <code>filter=\"reliability_rating>=2 and security_rating>=2 and sqale_rating>=2\"</code></li>" +
        "</ul>" +
        "To filter on project name or key, use the 'query' keyword, for instance : <code>filter='query = \"Sonar\"'</code>.<br>" +
        "<br>" +
        "To filter on any numeric metric, provide the metric key.<br>" +
        "Use the WS api/metrics/search to find the key of a metric.<br>" +
        "<br>" +
        "To filter on a rating, provide the corresponding metric key (ex: reliability_rating for reliability rating).<br>" +
        "The possible values are:" +
        "<ul>" +
        " <li>'1' for rating A</li>" +
        " <li>'2' for rating B</li>" +
        " <li>'3' for rating C</li>" +
        " <li>'4' for rating D</li>" +
        " <li>'5' for rating E</li>" +
        "</ul>" +
        "To filter on a Quality Gate status use the metric key 'alert_status'. Only the '=' operator can be used.<br>" +
        "The possible values are:" +
        "<ul>" +
        " <li>'OK' for Passed</li>" +
        " <li>'WARN' for Warning</li>" +
        " <li>'ERROR' for Failed</li>" +
        "</ul>" +
        "To filter on language keys use the language key: " +
        "<ul>" +
        " <li>to filter on a single language you can use 'language = java'</li>" +
        " <li>to filter on several languages you must use 'language IN (java, js)'</li>" +
        "</ul>" +
        "Use the WS api/languages/list to find the key of a language." +
        "To filter on tags use the 'tag' keyword:" +
        "<ul> " +
        " <li>to filter on one tag you can use <code>tag = finance</code></li>" +
        " <li>to filter on several tags you must use <code>tag in (offshore, java)</code></li>" +
        "</ul>");
    action.createParam(Param.SORT)
      .setDescription("Sort projects by numeric metric key, quality gate status (using '%s'), or by project name.<br/>" +
        "See '%s' parameter description for the possible metric values", ALERT_STATUS_KEY, PARAM_FILTER)
      .setDefaultValue(SORT_BY_NAME)
      .setExampleValue(NCLOC_KEY)
      .setSince("6.4");
    action.createParam(Param.ASCENDING)
      .setDescription("Ascending sort")
      .setBooleanPossibleValues()
      .setDefaultValue(true);
  }

  @Override
  public void handle(Request httpRequest, Response httpResponse) throws Exception {
    SearchProjectsWsResponse response = doHandle(toRequest(httpRequest));

    writeProtobuf(response, httpRequest, httpResponse);
  }

  private SearchProjectsWsResponse doHandle(SearchProjectsRequest request) {
    try (DbSession dbSession = dbClient.openSession(false)) {
      String organizationKey = request.getOrganization();
      if (organizationKey == null) {
        return handleForAnyOrganization(dbSession, request);
      } else {
        OrganizationDto organization = checkFoundWithOptional(
          dbClient.organizationDao().selectByKey(dbSession, organizationKey),
          "No organization for key '%s'", organizationKey);
        return handleForOrganization(dbSession, request, organization);
      }
    }
  }

  private SearchProjectsWsResponse handleForAnyOrganization(DbSession dbSession, SearchProjectsRequest request) {
    SearchResults searchResults = searchData(dbSession, request, null);
    Set<String> organizationUuids = searchResults.projects.stream().map(ComponentDto::getOrganizationUuid).collect(toSet());
    Map<String, OrganizationDto> organizationsByUuid = dbClient.organizationDao().selectByUuids(dbSession, organizationUuids)
      .stream()
      .collect(Collectors.uniqueIndex(OrganizationDto::getUuid));
    return buildResponse(request, searchResults, organizationsByUuid);
  }

  private SearchProjectsWsResponse handleForOrganization(DbSession dbSession, SearchProjectsRequest request, OrganizationDto organization) {
    SearchResults searchResults = searchData(dbSession, request, organization);
    return buildResponse(request, searchResults, ImmutableMap.of(organization.getUuid(), organization));
  }

  private SearchResults searchData(DbSession dbSession, SearchProjectsRequest request, @Nullable OrganizationDto organization) {
    Set<String> favoriteProjectUuids = loadFavoriteProjectUuids(dbSession);
    List<Criterion> criteria = FilterParser.parse(firstNonNull(request.getFilter(), ""));
    ProjectMeasuresQuery query = newProjectMeasuresQuery(criteria, hasFavoriteFilter(criteria) ? favoriteProjectUuids : null)
      .setSort(request.getSort())
      .setAsc(request.getAsc());
    Optional.ofNullable(organization)
      .map(OrganizationDto::getUuid)
      .ifPresent(query::setOrganizationUuid);

    queryValidator.validate(dbSession, query);

    SearchIdResult<String> esResults = index.search(query, new SearchOptions()
      .addFacets(request.getFacets())
      .setPage(request.getPage(), request.getPageSize()));

    List<String> projectUuids = esResults.getIds();
    Ordering<ComponentDto> ordering = Ordering.explicit(projectUuids).onResultOf(ComponentDto::uuid);
    List<ComponentDto> projects = ordering.immutableSortedCopy(dbClient.componentDao().selectByUuids(dbSession, projectUuids));
    Map<String, SnapshotDto> analysisByProjectUuid = getSnapshots(dbSession, request, projectUuids);
    return new SearchResults(projects, favoriteProjectUuids, esResults, analysisByProjectUuid, query);
  }

  private static boolean hasFavoriteFilter(List<Criterion> criteria) {
    return criteria.stream()
      .map(Criterion::getKey)
      .anyMatch(IS_FAVORITE_CRITERION::equalsIgnoreCase);
  }

  private Set<String> loadFavoriteProjectUuids(DbSession dbSession) {
    if (!userSession.isLoggedIn()) {
      return Collections.emptySet();
    }

    List<PropertyDto> props = dbClient.propertiesDao().selectByQuery(
      PropertyQuery.builder()
        .setUserId(userSession.getUserId())
        .setKey("favourite")
        .build(),
      dbSession);

    List<Long> favoriteDbIds = props.stream()
      .map(PropertyDto::getResourceId)
      .collect(Collectors.toList(props.size()));

    return dbClient.componentDao().selectByIds(dbSession, favoriteDbIds).stream()
      .filter(ComponentDto::isEnabled)
      .filter(f -> f.qualifier().equals(Qualifiers.PROJECT))
      .map(ComponentDto::uuid)
      .collect(Collectors.toSet());
  }

  private Map<String, SnapshotDto> getSnapshots(DbSession dbSession, SearchProjectsRequest request, List<String> projectUuids) {
    if (request.getAdditionalFields().contains(ANALYSIS_DATE)) {
      return dbClient.snapshotDao().selectLastAnalysesByRootComponentUuids(dbSession, projectUuids)
        .stream()
        .collect(Collectors.uniqueIndex(SnapshotDto::getComponentUuid));
    }
    return emptyMap();
  }

  private static SearchProjectsRequest toRequest(Request httpRequest) {
    SearchProjectsRequest.Builder request = SearchProjectsRequest.builder()
      .setOrganization(httpRequest.param(PARAM_ORGANIZATION))
      .setFilter(httpRequest.param(PARAM_FILTER))
      .setSort(httpRequest.mandatoryParam(Param.SORT))
      .setAsc(httpRequest.mandatoryParamAsBoolean(Param.ASCENDING))
      .setPage(httpRequest.mandatoryParamAsInt(Param.PAGE))
      .setPageSize(httpRequest.mandatoryParamAsInt(Param.PAGE_SIZE));
    if (httpRequest.hasParam(Param.FACETS)) {
      request.setFacets(httpRequest.paramAsStrings(Param.FACETS));
    }
    if (httpRequest.hasParam(Param.FIELDS)) {
      request.setAdditionalFields(httpRequest.paramAsStrings(FIELDS));
    }
    return request.build();
  }

  private SearchProjectsWsResponse buildResponse(SearchProjectsRequest request, SearchResults searchResults, Map<String, OrganizationDto> organizationsByUuid) {
    Function<ComponentDto, Component> dbToWsComponent = new DbToWsComponent(organizationsByUuid, searchResults.favoriteProjectUuids, searchResults.analysisByProjectUuid,
      userSession.isLoggedIn());

    return Stream.of(SearchProjectsWsResponse.newBuilder())
      .map(response -> response.setPaging(Common.Paging.newBuilder()
        .setPageIndex(request.getPage())
        .setPageSize(request.getPageSize())
        .setTotal(searchResults.total)))
      .map(response -> {
        searchResults.projects.stream()
          .map(dbToWsComponent)
          .forEach(response::addComponents);
        return response;
      })
      .map(response -> addFacets(searchResults, response))
      .map(SearchProjectsWsResponse.Builder::build)
      .findFirst()
      .orElseThrow(() -> new IllegalStateException("SearchProjectsWsResponse not built"));
  }

  private static SearchProjectsWsResponse.Builder addFacets(SearchResults searchResults, SearchProjectsWsResponse.Builder wsResponse) {
    Facets esFacets = searchResults.facets;
    EsToWsFacet esToWsFacet = new EsToWsFacet();

    searchResults.query.getLanguages().ifPresent(languages -> addMandatoryValuesToFacet(esFacets, FILTER_LANGUAGES, languages));
    searchResults.query.getTags().ifPresent(tags -> addMandatoryValuesToFacet(esFacets, FILTER_TAGS, tags));
    Common.Facets wsFacets = esFacets.getAll().entrySet().stream()
      .map(esToWsFacet)
      .collect(Collector.of(
        Common.Facets::newBuilder,
        Common.Facets.Builder::addFacets,
        (result1, result2) -> {
          throw new IllegalStateException("Parallel execution forbidden");
        },
        Common.Facets.Builder::build));

    wsResponse.setFacets(wsFacets);

    return wsResponse;
  }

  private static void addMandatoryValuesToFacet(Facets facets, String facetName, Iterable<String> mandatoryValues) {
    Map<String, Long> buckets = facets.get(facetName);
    if (buckets == null) {
      return;
    }
    for (String mandatoryValue : mandatoryValues) {
      if (!buckets.containsKey(mandatoryValue)) {
        buckets.put(mandatoryValue, 0L);
      }
    }
  }

  private static class EsToWsFacet implements Function<Entry<String, LinkedHashMap<String, Long>>, Common.Facet> {
    private final BucketToFacetValue bucketToFacetValue = new BucketToFacetValue();
    private final Common.Facet.Builder wsFacet = Common.Facet.newBuilder();

    @Override
    public Common.Facet apply(Entry<String, LinkedHashMap<String, Long>> esFacet) {
      wsFacet
        .clear()
        .setProperty(esFacet.getKey());
      LinkedHashMap<String, Long> buckets = esFacet.getValue();
      if (buckets != null) {
        buckets.entrySet()
          .stream()
          .map(bucketToFacetValue)
          .forEach(wsFacet::addValues);
      } else {
        wsFacet.addAllValues(Collections.emptyList());
      }

      return wsFacet.build();
    }
  }

  private static class BucketToFacetValue implements Function<Entry<String, Long>, Common.FacetValue> {
    private final Common.FacetValue.Builder facetValue;

    private BucketToFacetValue() {
      this.facetValue = Common.FacetValue.newBuilder();
    }

    @Override
    public Common.FacetValue apply(Entry<String, Long> bucket) {
      return facetValue
        .clear()
        .setVal(bucket.getKey())
        .setCount(bucket.getValue())
        .build();
    }
  }

  private static class DbToWsComponent implements Function<ComponentDto, Component> {
    private final Component.Builder wsComponent;
    private final Map<String, OrganizationDto> organizationsByUuid;
    private final Set<String> favoriteProjectUuids;
    private final boolean isUserLoggedIn;
    private final Map<String, SnapshotDto> analysisByProjectUuid;

    private DbToWsComponent(Map<String, OrganizationDto> organizationsByUuid, Set<String> favoriteProjectUuids, Map<String, SnapshotDto> analysisByProjectUuid,
      boolean isUserLoggedIn) {
      this.analysisByProjectUuid = analysisByProjectUuid;
      this.wsComponent = Component.newBuilder();
      this.organizationsByUuid = organizationsByUuid;
      this.favoriteProjectUuids = favoriteProjectUuids;
      this.isUserLoggedIn = isUserLoggedIn;
    }

    @Override
    public Component apply(ComponentDto dbComponent) {
      OrganizationDto organizationDto = organizationsByUuid.get(dbComponent.getOrganizationUuid());
      if (organizationDto == null) {
        throw new NotFoundException(format("Organization with uuid '%s' not found", dbComponent.getOrganizationUuid()));
      }
      wsComponent
        .clear()
        .setOrganization(organizationDto.getKey())
        .setId(dbComponent.uuid())
        .setKey(dbComponent.key())
        .setName(dbComponent.name());
      wsComponent.getTagsBuilder().addAllTags(dbComponent.getTags());

      SnapshotDto snapshotDto = analysisByProjectUuid.get(dbComponent.uuid());
      if (snapshotDto != null) {
        wsComponent.setAnalysisDate(DateUtils.formatDateTime(snapshotDto.getCreatedAt()));
      }

      if (isUserLoggedIn) {
        wsComponent.setIsFavorite(favoriteProjectUuids.contains(dbComponent.uuid()));
      }

      return wsComponent.build();
    }
  }

  private static class SearchResults {
    private final List<ComponentDto> projects;
    private final Set<String> favoriteProjectUuids;
    private final Facets facets;
    private final Map<String, SnapshotDto> analysisByProjectUuid;
    private final ProjectMeasuresQuery query;
    private final int total;

    private SearchResults(List<ComponentDto> projects, Set<String> favoriteProjectUuids, SearchIdResult<String> searchResults, Map<String, SnapshotDto> analysisByProjectUuid,
      ProjectMeasuresQuery query) {
      this.projects = projects;
      this.favoriteProjectUuids = favoriteProjectUuids;
      this.total = (int) searchResults.getTotal();
      this.facets = searchResults.getFacets();
      this.analysisByProjectUuid = analysisByProjectUuid;
      this.query = query;
    }
  }
}
