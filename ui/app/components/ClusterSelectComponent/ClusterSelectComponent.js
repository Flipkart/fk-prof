import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import Select, {Creatable} from 'react-select';

import fetchClustersAction from 'actions/ClusterActions';
import safeTraverse from 'utils/safeTraverse';

import styles from './ClusterSelectComponent.css';

import fetchPolicyClustersAction from 'actions/PolicyClusterActions';
import debounce from 'utils/debounce';

const noop = () => {};

class ClusterSelectComponent extends Component {
  componentDidMount () {
    const { app } = this.props;
    if (app) {
      this.props.isPolicyPage ? this.props.getPolicyClusters(app)() : this.props.getClusters(app)();
    }
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.app !== this.props.app || nextProps.isPolicyPage !== this.props.isPolicyPage) {
      nextProps.isPolicyPage ? this.props.getPolicyClusters(nextProps.app)() : this.props.getClusters(nextProps.app)();
    }
  }

  render() {
    const {onChange, clusters, policyClusters} = this.props;
    const finalClusters = this.props.isPolicyPage ? policyClusters : clusters;
    const clusterList = finalClusters.asyncStatus === 'SUCCESS'
      ? finalClusters.data.map(c => ({name: c})) : [];
    const valueOption = this.props.value && {name: this.props.value};
    return (
      <div>
        <label className={styles.label} htmlFor="cluster">Cluster</label>
        {this.props.isPolicyPage &&
        <Creatable
          id="cluster"
          clearable={false}
          options={clusterList}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getPolicyClusters(this.props.app), 500)}
          isLoading={finalClusters.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={finalClusters.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
          promptTextCreator={(label) => "Add cluster: " + label}
        />}
        {!this.props.isPolicyPage &&
        < Select
          id="cluster"
          clearable={false}
          options={clusterList}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getClusters(this.props.app), 500)}
          isLoading={finalClusters.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={finalClusters.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
        />
        }
      </div>
    );
  }
}

const mapStateToProps = (state, ownProps) => ({
  clusters: safeTraverse(state, ['clusters', ownProps.app]) || {},
  policyClusters: safeTraverse(state, ['policyClusters', ownProps.app]) || {}
});

const mapDispatchToProps = dispatch => ({
  getClusters: app => event => {dispatch(fetchClustersAction(app, event));},
  getPolicyClusters: app => event => dispatch(fetchPolicyClustersAction(app, event)),
});

ClusterSelectComponent.propTypes = {
  app: PropTypes.string,
  clusters: PropTypes.object.isRequired,
  policyClusters: PropTypes.object.isRequired,
  getClusters: PropTypes.func.isRequired,
  getPolicyClusters: PropTypes.func.isRequired,
  onChange: PropTypes.func,
  isPolicyPage: PropTypes.bool.isRequired,
};

export default connect(mapStateToProps, mapDispatchToProps)(ClusterSelectComponent);
