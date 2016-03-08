/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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
import $ from 'jquery';
import _ from 'underscore';
import Marionette from 'backbone.marionette';
import BaseFilters from './base-filters';
import MoreCriteriaFilters from './more-criteria-filters';

export default Marionette.CompositeView.extend({
  childViewContainer: '.navigator-filters-list',


  collectionEvents: {
    'change:enabled': 'changeEnabled'
  },


  getChildView (item) {
    return item.get('type') || BaseFilters.BaseFilterView;
  },


  childViewOptions () {
    return {
      filterBarView: this,
      app: this.options.app
    };
  },


  initialize () {
    Marionette.CompositeView.prototype.initialize.apply(this, arguments);

    const that = this;
    $('body').on('click', function () {
      that.hideDetails();
    });
    this.addMoreCriteriaFilter();

    key.filter = function (e) {
      let r = true;
      const el = $(e.target);
      const box = el.closest('.navigator-filter-details-inner');
      const tabbableSet = box.find(':tabbable');
      const isElFocusable = el.is(':input') || el.is('a');
      const isInsideDialog = el.closest('.ui-dialog').length > 0;
      if (isElFocusable) {
        if (!isInsideDialog && (e.keyCode === 9 || e.keyCode === 27)) {
          r = tabbableSet.index(el) >= tabbableSet.length - 1;
        } else {
          r = false;
        }
      }
      return r;
    };
    key('tab', 'list', function () {
      key.setScope('filters');
      that.selectFirst();
      return false;
    });
    key('shift+tab', 'filters', function () {
      that.selectPrev();
      return false;
    });
    key('tab', 'filters', function () {
      that.selectNext();
      return false;
    });
    key('escape', 'filters', function () {
      that.hideDetails();
      this.selected = -1;
      key.setScope('list');
    });
  },


  getEnabledFilters () {
    return this.$(this.childViewContainer).children()
        .not('.navigator-filter-disabled')
        .not('.navigator-filter-inactive')
        .not('.navigator-filter-favorite');
  },


  selectFirst () {
    this.selected = -1;
    this.selectNext();
  },


  selectPrev () {
    const filters = this.getEnabledFilters();
    if (this.selected > 0) {
      filters.eq(this.selected).blur();
      this.selected--;
      filters.eq(this.selected).click();
      this.$('.navigator-filter-submit').blur();
    }
  },


  selectNext () {
    const filters = this.getEnabledFilters();
    if (this.selected < filters.length - 1) {
      filters.eq(this.selected).blur();
      this.selected++;
      filters.eq(this.selected).click();
    } else {
      this.selected = filters.length;
      this.hideDetails();
      this.$('.navigator-filter-submit').focus();
    }
  },


  addMoreCriteriaFilter () {
    const disabledFilters = this.collection.where({ enabled: false });
    if (disabledFilters.length > 0) {
      this.moreCriteriaFilter = new BaseFilters.Filter({
        type: MoreCriteriaFilters.MoreCriteriaFilterView,
        enabled: true,
        optional: false,
        filters: disabledFilters
      });
      this.collection.add(this.moreCriteriaFilter);
    }
  },


  onAddChild (childView) {
    if (childView.model.get('type') === MoreCriteriaFilters.FavoriteFilterView) {
      $('.navigator-header').addClass('navigator-header-favorite');
    }
  },


  restoreFromQuery (q) {
    this.collection.each(function (item) {
      item.set('enabled', !item.get('optional'));
      item.view.clear();
      item.view.restoreFromQuery(q);
    });
  },


  hideDetails () {
    if (_.isObject(this.showedView)) {
      this.showedView.hideDetails();
    }
  },


  enableFilter (id) {
    const filter = this.collection.get(id);
    const filterView = filter.view;

    filterView.$el.detach().insertBefore(this.$('.navigator-filter-more-criteria'));
    filter.set('enabled', true);
    filterView.showDetails();
  },


  changeEnabled () {
    const disabledFilters = _.reject(this.collection.where({ enabled: false }), function (filter) {
      return filter.get('type') === MoreCriteriaFilters.MoreCriteriaFilterView;
    });

    if (disabledFilters.length === 0) {
      this.moreCriteriaFilter.set({ enabled: false }, { silent: true });
    } else {
      this.moreCriteriaFilter.set({ enabled: true }, { silent: true });
    }
    this.moreCriteriaFilter.set('filters', disabledFilters);
  }

});